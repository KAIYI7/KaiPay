package dev.kaipay.notificationprobe

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
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
    private val token = "invented_device_token_for_local_testing_only"
    private val deviceId = UUID.randomUUID().toString()
    private lateinit var originalCredentials: DeviceCredentialStorage
    @Before fun setup() {
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
                        if (result > 0) request.getOutputStream().write(
                            "HTTP/1.1 $result Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
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
        val original = requireNotNull(delivery.store.observe("invented-key", System.currentTimeMillis(), 123, deviceId))
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
        val original = requireNotNull(delivery.store.observe("invented-key", System.currentTimeMillis(), 123, deviceId))
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
        val original = requireNotNull(delivery.store.observe("invented-key", System.currentTimeMillis(), 123, deviceId))
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
}
