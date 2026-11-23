package dev.kaipay.notificationprobe

import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveDeliveryRecoveryTest {
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    @org.junit.After fun cleanup() { executor.shutdownNow() }
    @Test fun `capture only stop cancels periodic recovery even when delivery was already disabled`() {
        resetBridgeProcess()
        val app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder().setExecutor(executor).build())
        grantAccess(app)
        OwnerControlRuntime.get(app).set(capture = true, delivery = false)
        val delivery = LiveDelivery.get(app)
        assertTrue(delivery.awaitReady())
        val manager = WorkManager.getInstance(app)
        fun recovery() = manager.getWorkInfosForUniqueWork("kaipay-experimental-recovery").get(5, TimeUnit.SECONDS).single()
        fun drainControls() {
            val drained = CountDownLatch(1)
            (LiveDelivery::class.java.getDeclaredField("dispatcher").apply { isAccessible = true }.get(delivery) as DeliveryDispatcher)
                .control { drained.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
        }
        val scheduledId = recovery().id
        assertFalse(recovery().state.isFinished)
        // Disabling delivery alone must retain the capture owner's existing recovery work.
        delivery.disable()
        drainControls()
        assertEquals(scheduledId, recovery().id)
        assertFalse(recovery().state.isFinished)
        val rebinds = org.robolectric.shadows.ShadowNotificationListenerService.getRebindRequestCount()
        ProbeRuntime.disable(app)
        drainControls()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(OwnerControls(app.noBackupFilesDir).capture)
        assertFalse(OwnerControls(app.noBackupFilesDir).delivery)
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertEquals(scheduledId, recovery().id)
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, recovery().state)
        assertEquals(rebinds, org.robolectric.shadows.ShadowNotificationListenerService.getRebindRequestCount())
    }
    @Test fun `cold bootstrap requests bounded recovery only for saved capture with current access`() {
        resetBridgeProcess()
        val app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder().setExecutor(executor).build())
        grantAccess(app)
        OwnerControlRuntime.get(app).set(capture = true, delivery = false)
        val before = org.robolectric.shadows.ShadowNotificationListenerService.getRebindRequestCount()
        val delivery = LiveDelivery.get(app)
        assertTrue(delivery.awaitReady())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(before + 1, org.robolectric.shadows.ShadowNotificationListenerService.getRebindRequestCount())
        ProbeRuntime.requestRecovery(app)
        assertEquals(before + 1, org.robolectric.shadows.ShadowNotificationListenerService.getRebindRequestCount())
        assertTrue(ProbeRuntime.session.snapshot().enabled)
        assertFalse(ProbeRuntime.session.snapshot().connected)
        assertFalse(delivery.status().contains("Health: HEALTHY"))
        assertFalse(OwnerControls(app.noBackupFilesDir).delivery)
        ProbeRuntime.disable()
    }
    @Test fun `cold bootstrap leaves disabled or revoked capture off without rebind`() {
        resetBridgeProcess()
        val app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder().setExecutor(executor).build())
        grantAccess(app)
        OwnerControlRuntime.get(app).set(capture = false, delivery = false)
        val before = org.robolectric.shadows.ShadowNotificationListenerService.getRebindRequestCount()
        var delivery = LiveDelivery.get(app)
        assertTrue(delivery.awaitReady())
        assertEquals(before, org.robolectric.shadows.ShadowNotificationListenerService.getRebindRequestCount())
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        resetBridgeProcess()
        OwnerControlRuntime.get(app).set(capture = true, delivery = true)
        grantAccess(app, false)
        delivery = LiveDelivery.get(app)
        assertTrue(delivery.awaitReady())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(before, org.robolectric.shadows.ShadowNotificationListenerService.getRebindRequestCount())
        assertFalse(OwnerControls(app.noBackupFilesDir).capture)
        assertFalse(OwnerControls(app.noBackupFilesDir).delivery)
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        ProbeRuntime.disable()
    }
    @Test fun `completed owner stop cancels stale persisted pending rows before a later enable`() {
        resetBridgeProcess()
        val app = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder().setExecutor(executor).build())
        grantAccess(app)
        val deviceId = UUID.randomUUID().toString()
        val old = DeliveryStore(app.noBackupFilesDir)
        old.setEnabled(true)
        val event = requireNotNull(old.synthetic(deviceId, 135))
        // Simulate stop returned, then process died before its asynchronous queue mutation.
        OwnerControlRuntime.get(app).set(capture = true, delivery = false)
        val delivery = LiveDelivery.get(app)
        assertTrue(delivery.awaitReady())
        assertFalse(delivery.store.enabled)
        assertEquals(DeliveryStatus.FAILED, delivery.store.find(event.eventId)?.status)
        assertTrue(delivery.store.find(event.eventId)!!.note.startsWith("Cancelled by disable"))
        LiveDelivery::class.java.getDeclaredField("cachedConfig").apply { isAccessible = true }
            .set(delivery, DeliveryConfig("https://example.invalid", deviceId, "invented_token_for_recovery_test_only"))
        ProbeRuntime.session.setConnected(true)
        assertTrue(delivery.enable())
        val drained = CountDownLatch(1)
        (LiveDelivery::class.java.getDeclaredField("dispatcher").apply { isAccessible = true }.get(delivery) as DeliveryDispatcher)
            .control { drained.countDown() }
        assertTrue(drained.await(5, TimeUnit.SECONDS))
        assertEquals(DeliveryStatus.FAILED, delivery.store.find(event.eventId)?.status)
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("candidate-${event.eventId}")
            .get(5, TimeUnit.SECONDS).isEmpty())
        ProbeRuntime.disable()
    }
}
