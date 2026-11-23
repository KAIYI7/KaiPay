package dev.kaipay.notificationprobe

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class OwnerControlsTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun `default off explicit enable survives new instance and disabled flags never revive`() {
        val controls = OwnerControls(directory.root)
        assertFalse(controls.capture); assertFalse(controls.delivery)
        assertTrue(controls.set(capture = true, delivery = true))
        assertTrue(OwnerControls(directory.root).capture); assertTrue(OwnerControls(directory.root).delivery)
        assertTrue(controls.set(delivery = false))
        assertTrue(OwnerControls(directory.root).capture); assertFalse(OwnerControls(directory.root).delivery)
        controls.set(capture = false)
        val restarted = OwnerControls(directory.root)
        assertFalse(restarted.capture); assertFalse(restarted.delivery)
    }
    @Test fun `corrupt owner controls and interrupted disable marker fail closed`() {
        directory.root.resolve("owner-controls.json").writeText("{invented malformed state")
        var controls = OwnerControls(directory.root)
        assertFalse(controls.healthy); assertFalse(controls.capture); assertFalse(controls.delivery)
        assertTrue(controls.set(capture = true, delivery = true))
        directory.root.resolve("owner-controls.failed").writeText("DISABLED")
        controls = OwnerControls(directory.root)
        assertFalse(controls.healthy); assertFalse(controls.capture); assertFalse(controls.delivery)
    }
    @Test fun `independent concurrent stops preserve both disabled flags without lost update`() {
        val controls = OwnerControls(directory.root)
        controls.set(capture = true, delivery = true)
        val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val capture = executor.submit { start.await(); controls.set(capture = false) }
            val delivery = executor.submit { start.await(); controls.set(delivery = false) }
            start.countDown(); capture.get(5, TimeUnit.SECONDS); delivery.get(5, TimeUnit.SECONDS)
            val restarted = OwnerControls(directory.root)
            assertFalse(restarted.capture); assertFalse(restarted.delivery)
        } finally { executor.shutdownNow() }
    }
}
