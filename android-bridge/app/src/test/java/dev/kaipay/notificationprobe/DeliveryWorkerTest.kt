package dev.kaipay.notificationprobe

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.kaipay.probe.TngNotificationProfiles.KACHING_RULE_ID as KACHING
import dev.kaipay.probe.TngNotificationProfiles.V1_RULE_ID as V1
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Invented HTTP peers exercise the actual worker and persistence, without wallet transfers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DeliveryWorkerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var delivery: LiveDelivery
    private val workers = Executors.newFixedThreadPool(2)
    private var server: ServerSocket? = null
    private val bodies = CopyOnWriteArrayList<String>()
    private val paths = CopyOnWriteArrayList<String>()
    private var acknowledgement: ((JSONObject) -> String)? = null
    private val token = "invented_device_token_for_local_testing_only"
    private val deviceId = UUID.randomUUID().toString()
    private lateinit var originalCredentials: DeviceCredentialStorage
    @Before fun setup() {
        resetBridgeProcess()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder().setExecutor(workers).build())
        grantAccess(app)
        delivery = LiveDelivery.get(app); assertTrue(delivery.awaitReady())
        originalCredentials = LiveDelivery::class.java.getDeclaredField("credentials").apply { isAccessible = true }
            .get(delivery) as DeviceCredentialStorage
        delivery.store.clear(); delivery.store.setEnabled(true)
        // Tests inject a wholly invented runtime configuration; real storage uses Android Keystore.
        LiveDelivery::class.java.getDeclaredField("cachedConfig").apply { isAccessible = true }
            .set(delivery, DeliveryConfig("http://127.0.0.1:28082", deviceId, token))
        LiveDelivery::class.java.getDeclaredField("consent").apply { isAccessible = true }.setBoolean(delivery, true)
    }
    @After fun cleanup() {
        server?.close(); workers.shutdownNow()
        delivery.store.clear()
        LiveDelivery::class.java.getDeclaredField("credentials").apply { isAccessible = true }.set(delivery, originalCredentials)
        LiveDelivery::class.java.getDeclaredField("consent").apply { isAccessible = true }.setBoolean(delivery, false)
        ProbeRuntime.session.setConnected(false)
    }
    private fun candidate() = requireNotNull(delivery.store.synthetic(deviceId, 123))
    private fun dispatcher() = LiveDelivery::class.java.getDeclaredField("dispatcher").apply { isAccessible = true }
        .get(delivery) as DeliveryDispatcher
    private fun controlsComplete() {
        val complete = CountDownLatch(1); dispatcher().control { complete.countDown() }
        assertTrue(complete.await(5, TimeUnit.SECONDS))
    }
    private fun worker(id: String): DeliveryWorker = TestWorkerBuilder.from(app, DeliveryWorker::class.java, workers)
        .setInputData(Data.Builder().putString("eventId", id).build()).build()
    private fun serve(code: (String) -> Int) {
        val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", 28082)) }
        server = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    socket.accept().use { request ->
                        val input = request.getInputStream().bufferedReader(Charsets.UTF_8)
                        val path = input.readLine().split(' ')[1]
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        require(headers["authorization"] == "Bearer $token")
                        val length = requireNotNull(headers["content-length"]).toInt()
                        val content = CharArray(length); var offset = 0
                        while (offset < length) { val count = input.read(content, offset, length - offset); check(count > 0); offset += count }
                        paths.add(path); bodies.add(String(content))
                        val result = code(path)
                        if (result > 0) {
                            val original = if (path.endsWith("/invalidate")) delivery.store.find(path.substringAfter("/api/notification-candidates/").substringBefore("/"))!!.candidate.json() else JSONObject(String(content))
                            val ack = JSONObject().put("candidate", original).put("disposition", if (path.endsWith("/invalidate")) "INVALIDATED" else "UNMATCHED").toString()
                            val payload = if (result in 200..299) acknowledgement?.invoke(JSONObject(ack)) ?: ack else ""
                            request.getOutputStream().write(
                                "HTTP/1.1 $result Test\r\nContent-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$payload".toByteArray(Charsets.UTF_8))
                        }
                    }
                } catch (_: java.io.IOException) { }
            }
        }, "invented-candidate-http-peer").apply { isDaemon = true; start() }
    }
    @Test fun `worker uploads minimized synthetic event then ignores completed duplicate`() {
        serve { 200 }; val candidate = candidate()
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        assertEquals(DeliveryStatus.DELIVERED, delivery.store.find(candidate.eventId)?.status)
        assertEquals(candidate.eventId, JSONObject(bodies.single()).getString("eventId"))
        assertEquals("SYNTHETIC", JSONObject(bodies.single()).getString("source"))
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        assertEquals(1, bodies.size)
    }
    @Test fun `temporary rejection retries same event identity and permanent token rejection stops`() {
        var code = 503; serve { code }; val candidate = candidate()
        assertEquals(Result.retry(), worker(candidate.eventId).doWork())
        assertEquals(DeliveryStatus.PENDING, delivery.store.find(candidate.eventId)?.status)
        code = 401
        assertEquals(Result.failure(), worker(candidate.eventId).doWork())
        assertEquals(DeliveryStatus.FAILED, delivery.store.find(candidate.eventId)?.status)
        assertEquals(2, delivery.store.find(candidate.eventId)?.attempts)
        assertEquals(listOf(candidate.eventId, candidate.eventId), bodies.map { JSONObject(it).getString("eventId") })
        assertTrue(delivery.store.retry().isEmpty())
    }
    @Test fun `lost acknowledgement retry reuses persisted original payload`() {
        var acknowledgements = false; serve { if (acknowledgements) 200 else 0 }; val candidate = candidate()
        assertEquals(Result.retry(), worker(candidate.eventId).doWork())
        acknowledgements = true
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        assertTrue(bodies.size >= 2)
        assertTrue(bodies.all { JSONObject(it).getString("eventId") == candidate.eventId })
        assertEquals(DeliveryStatus.DELIVERED, delivery.store.find(candidate.eventId)?.status)
    }
    @Test fun `unavailable transport retains queue and restored receiver accepts same durable UUID`() {
        val candidate = candidate()
        assertEquals(Result.retry(), worker(candidate.eventId).doWork())
        assertEquals(DeliveryStatus.PENDING, delivery.store.find(candidate.eventId)?.status)
        assertEquals(candidate, DeliveryStore(app.noBackupFilesDir).find(candidate.eventId)?.candidate)
        serve { 200 }
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        assertEquals(candidate.eventId, JSONObject(bodies.single()).getString("eventId"))
        assertEquals(2, delivery.store.find(candidate.eventId)?.attempts)
    }
    @Test fun `exhausted upload budget stops traffic until bounded deliberate retry`() {
        serve { 200 }; val candidate = candidate()
        repeat(MAX_ATTEMPTS) { delivery.store.mark(candidate.eventId, DeliveryStatus.PENDING, "Prior ordinary failure", true) }
        assertEquals(Result.failure(), worker(candidate.eventId).doWork())
        assertEquals(DeliveryStatus.FAILED, delivery.store.find(candidate.eventId)?.status)
        assertTrue(bodies.isEmpty())
        assertEquals(listOf(candidate.eventId), delivery.store.retry())
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        assertEquals(1, bodies.size)
    }
    @Test fun `wrong event body empty success and oversized acknowledgement remain uncertain and retry bounded`() {
        serve { 200 }
        val candidate = candidate()
        val bodies = listOf<(JSONObject) -> String>(
            { "" },
            { it.getJSONObject("candidate").put("eventId", UUID.randomUUID().toString()); it.toString() },
            { it.getJSONObject("candidate").put("amountMinor", 124); it.toString() },
            { it.put("disposition", "MANUALLY_CONFIRMED").toString() },
            { "x".repeat(16_385) }
        )
        bodies.forEach { response ->
            acknowledgement = response
            assertEquals(Result.retry(), worker(candidate.eventId).doWork())
            assertFalse(delivery.store.find(candidate.eventId)!!.uploaded)
            assertNull(delivery.store.lastAcknowledgedAt)
        }
        acknowledgement = null
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        assertTrue(delivery.store.find(candidate.eventId)!!.uploaded)
        assertNotNull("queue directory: ${app.noBackupFilesDir}; status: ${delivery.status()}", DeliveryStore(app.noBackupFilesDir).lastAcknowledgedAt)
    }
    @Test fun `delayed acknowledgement is delivered evidence with no checkout eligibility claim`() {
        serve { 200 }; acknowledgement = { it.put("disposition", "DELAYED").toString() }
        val candidate = candidate()
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        assertEquals(DeliveryStatus.DELIVERED, delivery.store.find(candidate.eventId)?.status)
        assertTrue(delivery.store.find(candidate.eventId)!!.note.contains("not eligible"))
        assertNotNull(delivery.store.lastAcknowledgedAt)
    }
    @Test fun `disable barrier persists before blocked event dispatcher drains`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        OwnerControlRuntime.get(app).set(capture = true, delivery = true)
        dispatcher().control { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            delivery.disable()
            assertFalse("queue directory: ${app.noBackupFilesDir}; status: ${delivery.status()}", OwnerControls(app.noBackupFilesDir).delivery)
            assertTrue(OwnerControls(app.noBackupFilesDir).capture)
            assertFalse(delivery.mayTransmit(delivery.consentGeneration()))
        } finally { release.countDown() }
        controlsComplete()
        assertFalse(DeliveryStore(app.noBackupFilesDir).enabled)
    }
    @Test fun `concurrent disable cannot publish barrier inside another enable critical section`() {
        ProbeRuntime.session.setConnected(true); ProbeRuntime.session.setEnabled(true)
        OwnerControlRuntime.get(app).set(capture = true, delivery = true)
        val dispatcherStarted = CountDownLatch(1); val releaseDispatcher = CountDownLatch(1)
        dispatcher().control { dispatcherStarted.countDown(); check(releaseDispatcher.await(5, TimeUnit.SECONDS)) }
        assertTrue(dispatcherStarted.await(5, TimeUnit.SECONDS))
        val stopStarted = CountDownLatch(1)
        val stopper = Thread { stopStarted.countDown(); delivery.disable() }
        try {
            synchronized(delivery) {
                stopper.start(); assertTrue(stopStarted.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (stopper.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, stopper.state)
                // The old split barrier wrote false before waiting for this enable monitor.
                assertTrue(OwnerControls(app.noBackupFilesDir).delivery)
                assertTrue(delivery.enable())
            }
            stopper.join(5_000); assertFalse(stopper.isAlive)
            assertFalse("queue directory: ${app.noBackupFilesDir}; status: ${delivery.status()}", OwnerControls(app.noBackupFilesDir).delivery)
            assertFalse(delivery.mayTransmit(delivery.consentGeneration()))
            // Persisted event queue is still enabled because its dispatcher has not run the stop yet.
            assertTrue(DeliveryStore(app.noBackupFilesDir).enabled)
        } finally { releaseDispatcher.countDown(); stopper.join(5_000) }
        controlsComplete()
        assertFalse(DeliveryStore(app.noBackupFilesDir).enabled)
    }
    @Test fun `rapid disable reenable cannot revive cancelled queue after process interruption`() {
        ProbeRuntime.session.setConnected(true); ProbeRuntime.session.setEnabled(true)
        OwnerControlRuntime.get(app).set(capture = true, delivery = true)
        val old = candidate()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        dispatcher().control { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            delivery.disable()
            assertEquals(DeliveryStatus.PENDING, DeliveryStore(app.noBackupFilesDir).find(old.eventId)?.status)
            assertTrue(delivery.enable())
            // Simulate abrupt process death before either asynchronous control has run.
            val coldQueue = DeliveryStore(app.noBackupFilesDir)
            assertFalse(coldQueue.enabled)
            assertEquals(DeliveryStatus.FAILED, coldQueue.find(old.eventId)?.status)
            assertTrue(coldQueue.find(old.eventId)!!.note.startsWith("Cancelled by disable"))
            assertTrue(OwnerControls(app.noBackupFilesDir).delivery)
        } finally { release.countDown() }
        controlsComplete()
        assertEquals(DeliveryStatus.FAILED, delivery.store.find(old.eventId)?.status)
        assertTrue(androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork("candidate-${old.eventId}")
            .get(5, TimeUnit.SECONDS).isEmpty())
    }
    @Test fun `periodic unique reconciliation repairs persist before enqueue gap without event identity change`() {
        LiveDelivery::class.java.getDeclaredField("cachedConfig").apply { isAccessible = true }
            .set(delivery, DeliveryConfig("https://example.invalid", deviceId, token))
        val candidate = candidate()
        assertEquals(candidate, DeliveryStore(app.noBackupFilesDir).find(candidate.eventId)?.candidate)
        val manager = androidx.work.WorkManager.getInstance(app)
        assertTrue(manager.getWorkInfosForUniqueWork("candidate-${candidate.eventId}").get(5, TimeUnit.SECONDS).isEmpty())
        delivery.ensureRecovery(); delivery.ensureRecovery()
        delivery.reconcile(); controlsComplete()
        assertEquals(1, manager.getWorkInfosForUniqueWork("kaipay-experimental-recovery").get(5, TimeUnit.SECONDS).size)
        val work = manager.getWorkInfosForUniqueWork("candidate-${candidate.eventId}").get(5, TimeUnit.SECONDS)
        assertEquals(1, work.size)
        assertEquals(candidate, delivery.store.find(candidate.eventId)?.candidate)
        assertEquals(DeliveryStatus.PENDING, delivery.store.find(candidate.eventId)?.status)
    }
    @Test fun `health never claims current connectivity from old acknowledgement or disconnected listener`() {
        serve { 200 }; val candidate = candidate()
        ProbeRuntime.session.setConnected(true); ProbeRuntime.session.setEnabled(true)
        assertTrue(delivery.status(), delivery.status().contains("DEGRADED: pending delivery"))
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        ProbeRuntime.session.setConnected(false)
        assertFalse(delivery.status().contains("Health: HEALTHY"))
        assertTrue(delivery.status().contains("Transport availability: unknown between attempts"))
        ProbeRuntime.session.setConnected(true); ProbeRuntime.session.setEnabled(true)
        val ackClock = DeliveryStore::class.java.getDeclaredField("lastAcknowledgedAt").apply { isAccessible = true }
        ackClock.set(delivery.store, System.currentTimeMillis() + 86_400_000)
        assertTrue(delivery.status().contains("UNKNOWN: last acknowledgement is stale or device clock changed"))
        ackClock.set(delivery.store, System.currentTimeMillis() - 600_000)
        assertTrue(delivery.status().contains("UNKNOWN: last acknowledgement is stale or device clock changed"))
        delivery.store.mark(candidate.eventId, DeliveryStatus.FAILED, "Retry limit reached")
        assertTrue(delivery.status().contains("DEGRADED: failed or stale queue entries"))
        assertFalse(delivery.status().contains(token))
        assertFalse(delivery.status().contains(deviceId))
    }
    @Test fun `disabled delivery and revoked access do not transmit`() {
        serve { 200 }; val candidate = candidate()
        grantAccess(app, false)
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        assertEquals(0, bodies.size)
        grantAccess(app)
        LiveDelivery::class.java.getDeclaredField("consent").apply { isAccessible = true }.setBoolean(delivery, false)
        assertEquals(Result.success(), worker(candidate.eventId).doWork())
        assertEquals(0, bodies.size)
    }
    @Test fun `configuration off notice clears after successful enable while genuine warning remains`() {
        ProbeRuntime.session.setConnected(true); ProbeRuntime.session.setEnabled(true)
        LiveDelivery::class.java.getDeclaredField("credentials").apply { isAccessible = true }.set(delivery,
            object : DeviceCredentialStorage {
                override fun load(): DeliveryConfig? = null
                override fun save(config: DeliveryConfig) { }
                override fun clear() { }
            })
        delivery.configure(DeliveryConfig("https://example.invalid", deviceId, token))
        controlsComplete()
        assertTrue(delivery.status().contains("Device configuration saved; delivery remains off"))
        assertTrue(delivery.enable())
        controlsComplete()
        assertTrue(delivery.status().contains("Experimental delivery enabled: true"))
        assertFalse(delivery.status().contains("delivery remains off"))
        val warning = "Delivery callback capacity reached; candidate dropped"
        LiveDelivery::class.java.getDeclaredField("localError").apply { isAccessible = true }.set(delivery, warning)
        assertTrue(delivery.enable()); controlsComplete()
        assertTrue(delivery.status().contains(warning))
        assertTrue(delivery.status().contains("DEGRADED: inspect local diagnostic below"))
    }
    @Test fun `failed replacement cannot admit enable or reuse prior cached configuration`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        ProbeRuntime.session.setConnected(true); ProbeRuntime.session.setEnabled(true)
        LiveDelivery::class.java.getDeclaredField("credentials").apply { isAccessible = true }.set(delivery,
            object : DeviceCredentialStorage {
                override fun load(): DeliveryConfig? = null
                override fun save(config: DeliveryConfig) { throw java.io.IOException("invented configuration storage failure") }
                override fun clear() { }
            })
        dispatcher().control { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            delivery.configure(DeliveryConfig("https://example.invalid", deviceId, token))
            assertNull(delivery.config()); assertFalse(delivery.enable())
        } finally { release.countDown() }
        controlsComplete()
        assertNull(delivery.config()); assertFalse(delivery.enable())
        assertFalse(DeliveryStore(app.noBackupFilesDir).enabled)
    }
    @Test fun `earlier successful save cannot restore cache while later forget is pending`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val forgetting = CountDownLatch(1); val releaseForget = CountDownLatch(1)
        ProbeRuntime.session.setConnected(true); ProbeRuntime.session.setEnabled(true)
        LiveDelivery::class.java.getDeclaredField("credentials").apply { isAccessible = true }.set(delivery,
            object : DeviceCredentialStorage {
                override fun load(): DeliveryConfig? = null
                override fun save(config: DeliveryConfig) { }
                override fun clear() { forgetting.countDown(); check(releaseForget.await(5, TimeUnit.SECONDS)) }
            })
        dispatcher().control { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        delivery.configure(DeliveryConfig("https://example.invalid", deviceId, token)); delivery.forget()
        release.countDown()
        try {
            assertTrue(forgetting.await(5, TimeUnit.SECONDS))
            assertNull(delivery.config()); assertFalse(delivery.enable())
        } finally { releaseForget.countDown() }
        controlsComplete(); assertNull(delivery.config()); assertFalse(delivery.enable())
        assertFalse(DeliveryStore(app.noBackupFilesDir).enabled)
    }
    @Test fun `invalidation at original upload retry limit uses separate budget`() {
        serve { 200 }
        val original = requireNotNull(delivery.store.observe("invented-key", System.currentTimeMillis(), 123, deviceId, V1))
        repeat(MAX_ATTEMPTS) { delivery.store.mark(original.eventId, DeliveryStatus.PENDING, "Attempt", true) }
        delivery.store.uploaded(original.eventId)
        delivery.store.unknownUpdate("invented-key", System.currentTimeMillis() + 1)
        assertEquals(Result.success(), worker(original.eventId).doWork())
        assertEquals(listOf("/api/notification-candidates/${original.eventId}/invalidate"), paths.toList())
        assertEquals(DeliveryStatus.INVALIDATED, delivery.store.find(original.eventId)?.status)
        assertEquals(1, delivery.store.find(original.eventId)?.invalidationAttempts)
    }
    @Test fun `cancelled attempted original uses invalidation control only without replay`() {
        serve { 404 }
        val original = requireNotNull(delivery.store.observe("invented-key", System.currentTimeMillis(), 123, deviceId, V1))
        delivery.store.mark(original.eventId, DeliveryStatus.PENDING, "Attempted but acknowledgement lost", true)
        delivery.store.setEnabled(false); delivery.store.setEnabled(true)
        delivery.store.unknownUpdate("invented-key", System.currentTimeMillis() + 1)
        assertTrue(delivery.store.find(original.eventId)!!.controlOnly)
        assertEquals(Result.success(), worker(original.eventId).doWork())
        assertEquals(listOf("/api/notification-candidates/${original.eventId}/invalidate"), paths.toList())
        assertEquals(DeliveryStatus.INVALIDATED, delivery.store.find(original.eventId)?.status)
        assertFalse(delivery.store.find(original.eventId)!!.uploaded)
    }
    @Test fun `concurrent original and invalidation workers serialize and end invalidated`() {
        val received = CountDownLatch(1); val release = CountDownLatch(1)
        serve { path ->
            if (!path.endsWith("/invalidate")) { received.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            200
        }
        val original = requireNotNull(delivery.store.observe("invented-key", System.currentTimeMillis(), 123, deviceId, V1))
        val first = workers.submit<Result> { worker(original.eventId).doWork() }
        assertTrue(received.await(5, TimeUnit.SECONDS))
        delivery.store.unknownUpdate("invented-key", System.currentTimeMillis() + 1)
        val second = workers.submit<Result> { worker(original.eventId).doWork() }
        release.countDown()
        assertEquals(Result.success(), first.get(5, TimeUnit.SECONDS)); assertEquals(Result.success(), second.get(5, TimeUnit.SECONDS))
        assertEquals(DeliveryStatus.INVALIDATED, delivery.store.find(original.eventId)?.status)
        assertEquals(2, paths.size)
        assertTrue(paths.last().endsWith("/invalidate"))
    }
    @Test fun `both reviewed rules are queued each with its own profile`() {
        ProbeRuntime.session.setConnected(true); ProbeRuntime.session.setEnabled(true)
        // Invented values only. Both wordings classify as candidates and both may reach the wire.
        val local = dev.kaipay.probe.ProbeSession(profile = dev.kaipay.probe.TngNotificationProfiles.current).apply {
            setConnected(true); setEnabled(true)
        }
        fun post(key: String, title: String, text: String) = requireNotNull(local.posted(
            dev.kaipay.probe.ProbeParser.OFFICIAL_PACKAGE, key, System.currentTimeMillis()) { dev.kaipay.probe.Fields(title, text, text) })
        // Controls run before callbacks, so wait on a FIFO callback queued after the candidate itself.
        fun callbacksComplete() {
            val done = CountDownLatch(1)
            assertEquals(DeliveryDispatcher.Admission.ENQUEUED, dispatcher().candidate({ true }) { done.countDown() })
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
        val second = post("second", "Ka-ching! Incoming Money.", "RM45.67 received from ALEX DEMO for demo")
        assertEquals(KACHING, second.inspection!!.classification.ruleId)
        delivery.observed("second", second); callbacksComplete()
        val kaching = delivery.store.rows().single()
        assertEquals(KACHING, kaching.candidate.classifierProfile)
        assertEquals(4567L, kaching.candidate.amountMinor)
        assertEquals("RULE_MATCHED", kaching.candidate.evidenceQuality)
        val first = post("first", "You’ve received money!",
            "Alex Demo has transferred RM12.34 to you. Tap here to check the transaction details.")
        delivery.observed("first", first); callbacksComplete()
        val v1 = delivery.store.rows().single { it.candidate.eventId != kaching.candidate.eventId }
        assertEquals(V1, v1.candidate.classifierProfile)
        assertEquals(1234L, v1.candidate.amountMinor)
        // A rejected (refund) note, an over-range amount and a group summary stay off the wire.
        val refund = post("refund", "Ka-ching! Incoming Money.", "RM45.67 received from ALEX DEMO for 退款")
        assertNull(refund.inspection!!.classification.ruleId)
        delivery.observed("refund", refund); callbacksComplete()
        val large = post("large", "Ka-ching! Incoming Money.", "RM10,000.01 received from ALEX DEMO for demo")
        assertEquals(KACHING, large.inspection!!.classification.ruleId)
        delivery.observed("large", large); callbacksComplete()
        val grouped = requireNotNull(local.posted(dev.kaipay.probe.ProbeParser.OFFICIAL_PACKAGE, "grouped",
            System.currentTimeMillis(), dev.kaipay.probe.NotificationMetadata(grouped = true, groupSummary = true)) {
            dev.kaipay.probe.Fields("Ka-ching! Incoming Money.", "RM45.67 received from ALEX DEMO for demo",
                "RM45.67 received from ALEX DEMO for demo") })
        delivery.observed("grouped", grouped); callbacksComplete()
        assertEquals(2, delivery.store.rows().size)
    }
}
