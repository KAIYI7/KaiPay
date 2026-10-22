package dev.kaipay.notificationprobe

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArraySet

/** All network I/O executes on WorkManager's background executor. No payload or token logging. */
class DeliveryWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    @Volatile private var connection: HttpURLConnection? = null
    override fun doWork(): Result {
        val id = inputData.getString("eventId") ?: return Result.failure()
        return synchronized(eventLocks[(id.hashCode() and Int.MAX_VALUE) % eventLocks.size]) { deliver(id) }
    }
    private fun deliver(id: String): Result {
        val delivery = LiveDelivery.get(applicationContext)
        if (!delivery.awaitReady()) return Result.retry()
        val captured = delivery.consentGeneration()
        if (!delivery.mayTransmit(captured) || isStopped) return Result.success()
        val store = delivery.store
        val config = delivery.config() ?: run {
            store.mark(id, DeliveryStatus.FAILED, "Device credential unavailable"); return Result.failure()
        }
        val row = store.find(id) ?: return Result.success()
        if (row.status != DeliveryStatus.PENDING && row.status != DeliveryStatus.INVALIDATION_PENDING) return Result.success()
        if (!row.uploaded && !row.controlOnly && row.attempts >= MAX_ATTEMPTS) {
            store.mark(id, DeliveryStatus.FAILED, "Retry limit reached"); return Result.failure()
        }
        store.mark(id, row.status, "Attempting delivery", incrementAttempt = !row.uploaded && !row.controlOnly)
        try {
            if (!row.uploaded && !row.controlOnly) {
                if (!delivery.mayTransmit(captured) || isStopped) return Result.success()
                val response = post(endpoint(config.baseUrl, BuildConfig.DEBUG), config.token, row.candidate.json().toString())
                if (response.code !in 200..299) return rejection(response.code, id, delivery)
                store.acknowledged(id, acknowledged(response, row.candidate))
            }
            val latest = store.find(id) ?: return Result.success()
            if (latest.invalidate) {
                if (latest.invalidationAttempts >= MAX_ATTEMPTS) {
                    store.mark(id, DeliveryStatus.FAILED, "Retry limit reached"); return Result.failure()
                }
                if (!delivery.mayTransmit(captured) || isStopped) return Result.success()
                store.invalidationAttempt(id)
                val uri = URI(endpoint(config.baseUrl, BuildConfig.DEBUG).toString() + "/$id/invalidate")
                val response = post(uri, config.token, "{\"reason\":\"NOTIFICATION_CHANGED\"}")
                if (response.code == 404 && latest.controlOnly) {
                    store.mark(id, DeliveryStatus.INVALIDATED, "No server observation to invalidate; cancelled original never replayed")
                    return Result.success()
                }
                if (response.code !in 200..299) return rejection(response.code, id, delivery)
                if (acknowledged(response, row.candidate) != "INVALIDATED") throw IOException("Uncertain invalidation acknowledgement")
                store.acknowledged(id, "INVALIDATED", invalidation = true)
            }
            return Result.success()
        } catch (_: javax.net.ssl.SSLException) {
            store.mark(id, DeliveryStatus.FAILED, "TLS certificate or protocol rejection")
            return Result.failure()
        } catch (_: IOException) {
            if (!delivery.mayTransmit(captured) || isStopped) return Result.success()
            store.mark(id, if (store.find(id)?.invalidate == true) DeliveryStatus.INVALIDATION_PENDING else DeliveryStatus.PENDING,
                "Connection unavailable; bounded retry pending")
            return Result.retry()
        } catch (_: Exception) {
            store.mark(id, DeliveryStatus.FAILED, "Local configuration or storage rejection")
            return Result.failure()
        } finally { connection?.disconnect(); connection = null }
    }
    private fun rejection(code: Int, id: String, delivery: LiveDelivery): Result {
        val retryable = code == 408 || code == 429 || code in 500..599
        delivery.store.mark(id, if (retryable) {
            if (delivery.store.find(id)?.invalidate == true) DeliveryStatus.INVALIDATION_PENDING else DeliveryStatus.PENDING
        } else DeliveryStatus.FAILED,
            if (retryable) "Temporary server rejection; retry pending" else "Permanent server rejection (HTTP $code)")
        return if (retryable) Result.retry() else Result.failure()
    }
    private data class Response(val code: Int, val body: String)
    private fun acknowledged(response: Response, expected: Candidate): String {
        try {
            val value = JSONObject(response.body)
            val actual = value.getJSONObject("candidate")
            val wanted = expected.json()
            if (!wanted.keys().asSequence().all { actual.has(it) && actual.get(it).toString() == wanted.get(it).toString() })
                throw IOException("Uncertain event acknowledgement")
            val disposition = value.getString("disposition")
            if (disposition !in setOf("MATCHED", "AMBIGUOUS", "UNMATCHED", "EXPIRED", "UPDATE_IGNORED", "DELAYED", "INVALIDATED"))
                throw IOException("Uncertain acknowledgement disposition")
            return disposition
        } catch (_: Exception) { throw IOException("Uncertain receiver acknowledgement") }
    }
    private fun post(uri: URI, token: String, body: String): Response {
        val active = uri.toURL().openConnection() as HttpURLConnection
        connection = active; connections.add(active)
        return try {
            active.connectTimeout = 5_000; active.readTimeout = 5_000; active.instanceFollowRedirects = false
            active.requestMethod = "POST"; active.doOutput = true
            active.setRequestProperty("Authorization", "Bearer $token")
            active.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            active.setFixedLengthStreamingMode(bytes.size)
            active.outputStream.use { it.write(bytes) }
            val code = active.responseCode
            val responseBody = if (code in 200..299) active.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 16_384) throw IOException("Acknowledgement size exceeded")
                    output.write(buffer, 0, count)
                }
                String(output.toByteArray(), Charsets.UTF_8)
            } else ""
            Response(code, responseBody)
        } finally { connections.remove(active); active.disconnect(); connection = null }
    }
    override fun onStopped() { connection?.disconnect(); super.onStopped() }
    companion object {
        private val eventLocks = Array(32) { Any() }
        private val connections = CopyOnWriteArraySet<HttpURLConnection>()
        internal fun cancelConnections() { connections.forEach { it.disconnect() } }
    }
}
