package dev.kaipay.notificationprobe

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One persistence owner. Bounded callbacks cannot displace ordered consent/configuration controls. */
internal class DeliveryDispatcher(private val onFailure: () -> Unit = {}) {
    enum class Admission { ENQUEUED, STALE, FULL }
    private data class Callback(val valid: () -> Boolean, val action: () -> Unit)
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val controls = ArrayDeque<() -> Unit>()
    private val callbacks = ArrayDeque<Callback>()
    private var closed = false
    private val thread = Thread({ run() }, "kaipay-candidate-persistence").apply { isDaemon = true; start() }

    fun control(dropStaleCallbacks: Boolean = false, action: () -> Unit) = lock.withLock {
        if (closed) throw RejectedExecutionException("Delivery dispatcher closed")
        if (dropStaleCallbacks) callbacks.removeAll { !it.valid() }
        controls.addLast(action)
        available.signal()
    }
    fun candidate(valid: () -> Boolean, action: () -> Unit): Admission = lock.withLock {
        if (closed || !valid()) return Admission.STALE
        if (callbacks.size >= 128) return Admission.FULL
        callbacks.addLast(Callback(valid, action)); available.signal(); Admission.ENQUEUED
    }
    private fun run() {
        while (true) {
            val action = lock.withLock {
                while (!closed && controls.isEmpty() && callbacks.isEmpty()) available.await()
                if (closed) return
                if (controls.isNotEmpty()) controls.removeFirst() else {
                    val callback = callbacks.removeFirst()
                    if (callback.valid()) callback.action else null
                }
            }
            if (action != null) try { action() } catch (_: Exception) { onFailure() }
        }
    }
    internal fun close() = lock.withLock { closed = true; controls.clear(); callbacks.clear(); available.signalAll() }
}
