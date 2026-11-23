package dev.kaipay.notificationprobe

import android.content.Context
import android.os.SystemClock
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.google.common.util.concurrent.ListenableFuture
import java.time.Instant
import java.util.concurrent.Executor

/** Local diagnostic values only: no notification text, event identifiers, credentials or network probes. */
internal object OperationalHealth {
    fun listener(access: Boolean, known: Boolean, connected: Boolean): String = when {
        !access -> "DISCONNECTED: notification access unavailable"
        connected -> "CONNECTED: callback observed in this process; no continuous receipt guarantee"
        known -> "DISCONNECTED: listener callback observed"
        else -> "UNKNOWN: connection not observed in this process"
    }

    fun acknowledgement(at: Long?, now: Long): String = when {
        at == null -> "UNKNOWN: no authorized acknowledgement recorded"
        now - at !in 0..300_000 -> "UNKNOWN: last acknowledgement is stale or device clock changed"
        else -> "RECENT: authorized delivery acknowledged; current reachability UNKNOWN"
    }

    fun oldestAge(rows: List<QueueEntry>, now: Long): String {
        if (rows.isEmpty()) return "none"
        val oldest = rows.minOf { Instant.parse(it.candidate.observedAt).toEpochMilli() }
        return if (now < oldest) "UNKNOWN: device clock changed" else "${(now - oldest) / 1000}s"
    }

    fun workers(infos: List<WorkInfo>): String {
        val active = infos.filter { !it.state.isFinished }
        return "RUNNING=${active.count { it.state == WorkInfo.State.RUNNING }}; " +
            "ENQUEUED=${active.count { it.state == WorkInfo.State.ENQUEUED }}; " +
            "RETRY_ENQUEUED=${active.count { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }}; " +
            "BLOCKED_DEPENDENCY=${active.count { it.state == WorkInfo.State.BLOCKED }}" +
            "; constraints/backoff eligibility and exact run time UNKNOWN"
    }
}

/** At most one asynchronous query per five seconds, only while local status is read.
 * Names come from the bounded current queue. Finished work history is excluded in the database query.
 * A recovery job is never represented as delivery work or proof of listener connection.
 */
internal class WorkerHealth(
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val query: (Context, WorkQuery) -> ListenableFuture<List<WorkInfo>> = { context, request ->
        WorkManager.getInstance(context).getWorkInfos(request)
    }
) {
    private data class Sample(val at: Long, val delivery: String, val recovery: String)
    @Volatile private var sample: Sample? = null
    private var lastQuery: Long? = null
    private var inFlight: ListenableFuture<List<WorkInfo>>? = null

    @Synchronized fun refresh(context: Context, rows: List<QueueEntry>) {
        val now = elapsed()
        val previous = lastQuery
        if (previous != null && now - previous in 0 until 5_000) return
        if (inFlight != null) {
            if (previous != null && now - previous in 0 until 10_000) return
            val expired = inFlight; inFlight = null; expired?.cancel(false)
        }
        lastQuery = now
        val names = rows.take(DELIVERY_CAPACITY).flatMap {
            listOf("candidate-${it.candidate.eventId}", "invalidate-${it.candidate.eventId}")
        } + RECOVERY
        try {
            val future = query(context, WorkQuery.Builder.fromUniqueWorkNames(names)
                .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)).build())
            inFlight = future
            future.addListener({
                // Listener only runs after completion; get() never blocks the UI thread.
                synchronized(this) {
                    if (inFlight === future) {
                        try {
                            val infos = future.get()
                            sample = Sample(elapsed(), OperationalHealth.workers(infos.filter { DELIVERY_TAG in it.tags }),
                                OperationalHealth.workers(infos.filter { DELIVERY_TAG !in it.tags }))
                        } catch (_: Exception) { sample = null }
                        inFlight = null
                    }
                }
            }, Executor { it.run() })
        } catch (_: Exception) { sample = null; inFlight = null }
    }

    fun status(): String {
        val value = sample ?: return "Delivery worker: UNKNOWN: awaiting WorkManager sample\nRecovery worker: UNKNOWN"
        val age = elapsed() - value.at
        if (age !in 0..15_000) return "Delivery worker: UNKNOWN: WorkManager sample stale\nRecovery worker: UNKNOWN: sample stale"
        return "WorkManager sample age: ${age / 1000}s\nDelivery worker: ${value.delivery}\nRecovery worker: ${value.recovery}"
    }

    companion object {
        private const val RECOVERY = "kaipay-experimental-recovery"
        private const val DELIVERY_TAG = "kaipay-experimental-delivery"
    }
}
