package dev.kaipay.notificationprobe

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DeliveryDispatcherTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun `saturated callback queue cannot reject durable disable and restart stays off`() {
        val store = DeliveryStore(directory.root); store.setEnabled(true)
        val enabled = AtomicBoolean(true); val generation = AtomicLong(0)
        val started = CountDownLatch(1); val release = CountDownLatch(1); val disabled = CountDownLatch(1)
        val callbacksRun = AtomicInteger(0)
        val dispatcher = DeliveryDispatcher()
        try {
            dispatcher.control { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(128) {
                assertEquals(DeliveryDispatcher.Admission.ENQUEUED, dispatcher.candidate({ enabled.get() && generation.get() == 0L }) {
                    callbacksRun.incrementAndGet()
                })
            }
            assertEquals(DeliveryDispatcher.Admission.FULL, dispatcher.candidate({ true }) { callbacksRun.incrementAndGet() })
            enabled.set(false); generation.incrementAndGet()
            dispatcher.control(dropStaleCallbacks = true) { store.setEnabled(false); disabled.countDown() }
            assertEquals(DeliveryDispatcher.Admission.STALE,
                dispatcher.candidate({ enabled.get() && generation.get() == 0L }) { callbacksRun.incrementAndGet() })
            release.countDown(); assertTrue(disabled.await(5, TimeUnit.SECONDS))
            assertFalse(DeliveryStore(directory.root).enabled)
            assertEquals(0, callbacksRun.get())
            assertTrue(DeliveryStore(directory.root).rows().isEmpty())
        } finally { release.countDown(); dispatcher.close() }
    }
    @Test fun `disable preserves bootstrap and earlier configuration cleanup controls in order`() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val complete = CountDownLatch(1)
        val order = CopyOnWriteArrayList<String>(); val enabled = AtomicBoolean(true)
        val dispatcher = DeliveryDispatcher()
        try {
            dispatcher.control { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); order.add("bootstrap") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            dispatcher.control { order.add("configure") }
            dispatcher.control { order.add("forget") }
            repeat(128) { dispatcher.candidate({ enabled.get() }) { order.add("candidate") } }
            enabled.set(false)
            dispatcher.control(dropStaleCallbacks = true) { order.add("disable"); complete.countDown() }
            release.countDown(); assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("bootstrap", "configure", "forget", "disable"), order.toList())
        } finally { release.countDown(); dispatcher.close() }
    }
    @Test fun `racing old generation callback cannot be admitted after consent off`() {
        val enabled = AtomicBoolean(true); val generation = AtomicLong(0)
        val captured = CountDownLatch(1); val release = CountDownLatch(1); val complete = CountDownLatch(1)
        val result = arrayOfNulls<DeliveryDispatcher.Admission>(1)
        val dispatcher = DeliveryDispatcher()
        try {
            Thread {
                val observed = generation.get(); captured.countDown(); check(release.await(5, TimeUnit.SECONDS))
                result[0] = dispatcher.candidate({ enabled.get() && generation.get() == observed }) { fail("Stale callback executed") }
                complete.countDown()
            }.start()
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            enabled.set(false); generation.incrementAndGet()
            dispatcher.control(dropStaleCallbacks = true) { }
            release.countDown(); assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals(DeliveryDispatcher.Admission.STALE, result[0])
        } finally { release.countDown(); dispatcher.close() }
    }
}
