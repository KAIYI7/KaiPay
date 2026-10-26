package dev.kaipay.notificationprobe

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequest
import androidx.work.ExistingWorkPolicy
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Invented local signals, never device observations or proof of Xiaomi lifecycle behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class OperationalHealthTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val executor = Executors.newFixedThreadPool(2)
    @Before fun setup() {
        resetBridgeProcess()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder().setExecutor(executor).build())
        grantAccess(app)
    }
    @After fun cleanup() { resetBridgeProcess(); executor.shutdownNow() }

    @Test fun `listener remains unknown on restart even with permission process queue and rebind`() {
        OwnerControlRuntime.get(app).set(capture = true, delivery = false)
        val delivery = LiveDelivery.get(app)
        assertTrue(delivery.awaitReady())
        val status = delivery.status()
        assertTrue(status, status.contains("Application process: RUNNING"))
        assertTrue(status.contains("Notification access: GRANTED"))
        assertTrue(status.contains("Listener: UNKNOWN: connection not observed in this process"))
        assertTrue(status.contains("Last listener state callback in this process: unknown"))
        assertTrue(status.contains("Queue backlog: 0"))
        assertFalse(status.contains("HEALTHY"))
        assertTrue(status.contains("Xiaomi may need owner-supervised manual reconnection"))
    }

    @Test fun `connected and disconnected callbacks remain separate from consent and access`() {
        val delivery = LiveDelivery.get(app); assertTrue(delivery.awaitReady())
        ProbeRuntime.connected(app)
        assertNotNull(ProbeRuntime.listenerChangedAt)
        assertTrue(delivery.status().contains("Listener: CONNECTED: callback observed in this process"))
        assertTrue(delivery.status().contains("Experimental delivery enabled: false"))
        ProbeRuntime.disconnected(app)
        assertTrue(delivery.status().contains("Listener: DISCONNECTED: listener callback observed"))
        // Revocation overrides the last connected callback without fabricating another callback.
        ProbeRuntime.connected(app)
        grantAccess(app, false)
        assertTrue(delivery.status().contains("Notification access: UNAVAILABLE"))
        assertTrue(delivery.status().contains("Listener: DISCONNECTED: notification access unavailable"))
    }

    @Test fun `acknowledgement freshness never proves current transport or financial confirmation`() {
        val now = 1_800_000_000_000L
        assertTrue(OperationalHealth.acknowledgement(null, now).startsWith("UNKNOWN"))
        assertTrue(OperationalHealth.acknowledgement(now - 300_000, now).contains("current reachability UNKNOWN"))
        assertTrue(OperationalHealth.acknowledgement(now - 300_001, now).startsWith("UNKNOWN"))
        assertTrue(OperationalHealth.acknowledgement(now + 1, now).startsWith("UNKNOWN"))
        val delivery = LiveDelivery.get(app); assertTrue(delivery.awaitReady())
        assertTrue(delivery.status().contains("Notification evidence is unverified"))
    }

    @Test fun `oldest pending age exposes queue delay and uncertain clock without event data`() {
        var now = 1_800_000_000_000L
        val store = DeliveryStore(app.noBackupFilesDir, { now }); store.setEnabled(true)
        store.synthetic(UUID.randomUUID().toString(), 123)
        now += 65_000
        store.synthetic(UUID.randomUUID().toString(), 321)
        assertEquals("65s", OperationalHealth.oldestAge(store.rows(), now))
        assertEquals("none", OperationalHealth.oldestAge(emptyList(), now))
        assertTrue(OperationalHealth.oldestAge(store.rows(), now - 66_000).startsWith("UNKNOWN"))
    }

    @Test fun `work dimensions distinguish running retry enqueued and dependency block`() {
        fun info(state: WorkInfo.State, attempts: Int = 0) = WorkInfo(UUID.randomUUID(), state, emptySet(), Data.EMPTY, Data.EMPTY, attempts)
        val output = OperationalHealth.workers(listOf(info(WorkInfo.State.RUNNING), info(WorkInfo.State.ENQUEUED, 2),
            info(WorkInfo.State.ENQUEUED), info(WorkInfo.State.BLOCKED), info(WorkInfo.State.SUCCEEDED)))
        assertTrue(output.contains("RUNNING=1; ENQUEUED=2; RETRY_ENQUEUED=1; BLOCKED_DEPENDENCY=1"))
        assertTrue(output.contains("exact run time UNKNOWN"))
        assertFalse(output.contains("CONNECTED"))
    }

    @Test fun `real WorkManager sample includes queued event and excludes unrelated work`() {
        val delivery = LiveDelivery.get(app); assertTrue(delivery.awaitReady())
        val store = delivery.store; store.setEnabled(true)
        val candidate = requireNotNull(store.synthetic(UUID.randomUUID().toString(), 123))
        val manager = WorkManager.getInstance(app)
        fun delayed() = OneTimeWorkRequest.Builder(DeliveryWorker::class.java)
            .setInitialDelay(1, TimeUnit.DAYS).addTag("kaipay-experimental-delivery").build()
        val owned = delayed(); val unrelated = delayed()
        manager.enqueueUniqueWork("candidate-${candidate.eventId}", ExistingWorkPolicy.KEEP, owned).result.get(5, TimeUnit.SECONDS)
        manager.enqueueUniqueWork("unrelated-invented-name", ExistingWorkPolicy.KEEP, unrelated).result.get(5, TimeUnit.SECONDS)
        // Even a failed/expired queue row may still have an unfinished scheduler entry.
        store.mark(candidate.eventId, DeliveryStatus.FAILED, "Invented failure pending scheduler completion")
        try {
            delivery.status()
            // Bounded wait observes an asynchronous database result, not a scheduling/race guarantee.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (delivery.status().contains("awaiting WorkManager sample") && System.nanoTime() < deadline) Thread.sleep(10)
            val output = delivery.status()
            assertTrue(output, output.contains("Delivery worker: RUNNING=0; ENQUEUED=1; RETRY_ENQUEUED=0"))
            assertTrue(output.contains("Recovery worker: RUNNING=0; ENQUEUED=0"))
            assertFalse(output.contains(candidate.eventId)); assertFalse(output.contains(candidate.deviceId))
        } finally {
            manager.cancelWorkById(owned.id).result.get(5, TimeUnit.SECONDS)
            manager.cancelWorkById(unrelated.id).result.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun `worker query is asynchronous scoped throttled and stale until fresh completion`() {
        var elapsed = 10_000L
        val requests = mutableListOf<WorkQuery>()
        val futures = mutableListOf<SettableFuture<List<WorkInfo>>>()
        val health = WorkerHealth({ elapsed }) { _, request ->
            requests += request
            SettableFuture.create<List<WorkInfo>>().also { futures += it }
        }
        val store = DeliveryStore(app.noBackupFilesDir); store.setEnabled(true)
        val candidate = requireNotNull(store.synthetic(UUID.randomUUID().toString(), 123))
        health.refresh(app, store.rows())
        assertTrue(health.status().contains("UNKNOWN: awaiting WorkManager sample"))
        assertEquals(setOf("candidate-${candidate.eventId}", "invalidate-${candidate.eventId}", "kaipay-experimental-recovery"),
            requests.single().uniqueWorkNames.toSet())
        assertEquals(setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED), requests.single().states.toSet())
        futures.single().set(listOf(WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED,
            setOf("kaipay-experimental-delivery")), WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet())))
        assertTrue(health.status().contains("Delivery worker: RUNNING=0; ENQUEUED=1"))
        assertTrue(health.status().contains("Recovery worker: RUNNING=1; ENQUEUED=0"))
        elapsed += 4_999; health.refresh(app, store.rows()); assertEquals(1, requests.size)
        elapsed += 1; health.refresh(app, store.rows()); assertEquals(2, requests.size)
        elapsed += 15_001
        assertTrue(health.status().contains("UNKNOWN: WorkManager sample stale"))
        health.refresh(app, store.rows()); assertTrue(futures[1].isCancelled)
        assertEquals(3, requests.size)
        futures[2].setException(IllegalStateException("invented failure"))
        assertTrue(health.status().contains("UNKNOWN"))
    }
}
