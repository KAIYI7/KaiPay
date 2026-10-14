package dev.kaipay.notificationprobe

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val DELIVERY_TTL = 86_400_000L
internal const val DELIVERY_CAPACITY = 100
internal const val MAX_ATTEMPTS = 10
internal enum class DeliveryStatus { PENDING, DELIVERED, INVALIDATION_PENDING, INVALIDATED, FAILED, EXPIRED }

internal data class DeliveryConfig(val baseUrl: String, val deviceId: String, val token: String) {
    override fun toString() = "DeliveryConfig(redacted)"
    fun validate(debug: Boolean): DeliveryConfig {
        require(baseUrl.length <= 1024)
        require(UUID.fromString(deviceId).toString() == deviceId)
        require(token.matches(Regex("[A-Za-z0-9_-]{32,256}")))
        endpoint(baseUrl, debug)
        return this
    }
}

internal fun endpoint(baseUrl: String, debug: Boolean): URI {
    val uri = URI(baseUrl)
    require(uri.isAbsolute && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null &&
        uri.rawFragment == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"))
    require((uri.scheme == "https" && (uri.port == -1 || uri.port in 1..65535)) ||
        (debug && uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 28082))
    return URI(uri.scheme, null, uri.host, uri.port, "/api/notification-candidates", null, null)
}

internal data class Candidate(
    val eventId: String, val notificationId: String, val sequence: Int, val deviceId: String,
    val source: String, val classifierProfile: String, val amountMinor: Long,
    val observedAt: String, val evidenceQuality: String
) {
    fun json(): JSONObject = JSONObject().put("eventId", eventId).put("notificationId", notificationId)
        .put("sequence", sequence).put("deviceId", deviceId).put("source", source)
        .put("classification", "INCOMING_CANDIDATE").put("classifierProfile", classifierProfile)
        .put("amountMinor", amountMinor).put("observedAt", observedAt).put("evidenceQuality", evidenceQuality)
    override fun toString() = "Candidate(minimized)"
    companion object {
        fun read(value: JSONObject) = Candidate(value.getString("eventId"), value.getString("notificationId"),
            value.getInt("sequence"), value.getString("deviceId"), value.getString("source"),
            value.getString("classifierProfile"), value.getLong("amountMinor"), value.getString("observedAt"),
            value.getString("evidenceQuality"))
    }
}
internal data class QueueEntry(val candidate: Candidate, var status: DeliveryStatus = DeliveryStatus.PENDING,
    var attempts: Int = 0, var note: String = "Queued; unverified notification evidence", var invalidate: Boolean = false,
    var uploaded: Boolean = false, var manualRetries: Int = 0, var invalidationAttempts: Int = 0, var controlOnly: Boolean = false)
internal data class Lifecycle(val digest: String, val notificationId: String, var lastPostTime: Long,
    var amount: Long, var sequence: Int, var closed: Boolean = false, var fingerprint: String = "")

/** Bounded private snapshot. It never contains notification text, Android keys or credentials. */
internal class DeliveryStore(directory: File, private val clock: () -> Long = System::currentTimeMillis,
    private val beforeWrite: () -> Unit = {}, private val onFailure: () -> Unit = {}) {
    private val file = PrivateAtomicFile(File(directory, "candidate-queue.json"))
    private val failureMarker = File(directory, "delivery-storage.failed")
    private var secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val entries = linkedMapOf<String, QueueEntry>()
    private val lifecycles = linkedMapOf<String, Lifecycle>()
    var enabled = false; private set
    var generation = 0L; private set
    var warning = ""; private set
    var healthy = true; private set

    init {
        directory.mkdirs()
        if (file.baseFile.exists()) {
            try {
                val bytes = file.read(524_288)
                require(bytes.size <= 524_288)
                val root = JSONObject(String(bytes, Charsets.UTF_8))
                secret = android.util.Base64.decode(root.getString("secret"), android.util.Base64.NO_WRAP)
                require(secret.size == 32)
                enabled = root.getBoolean("enabled"); generation = root.getLong("generation")
                val rows = root.getJSONArray("entries"); require(rows.length() <= DELIVERY_CAPACITY)
                repeat(rows.length()) { i ->
                    val row = rows.getJSONObject(i)
                    val candidate = Candidate.read(row.getJSONObject("candidate"))
                    require(candidate.amountMinor in 1..1_000_000 && candidate.sequence in 1..1_000_000)
                    UUID.fromString(candidate.eventId); UUID.fromString(candidate.notificationId); UUID.fromString(candidate.deviceId)
                    Instant.parse(candidate.observedAt)
                    entries[candidate.eventId] = QueueEntry(candidate, DeliveryStatus.valueOf(row.getString("status")),
                        row.getInt("attempts"), row.getString("note"), row.optBoolean("invalidate"), row.optBoolean("uploaded"),
                        row.optInt("manualRetries"), row.optInt("invalidationAttempts"), row.optBoolean("controlOnly"))
                }
                val lives = root.getJSONArray("lifecycles"); require(lives.length() <= DELIVERY_CAPACITY)
                repeat(lives.length()) { i ->
                    val row = lives.getJSONObject(i)
                    val life = Lifecycle(row.getString("digest"), row.getString("notificationId"), row.getLong("postTime"),
                        row.getLong("amount"), row.getInt("sequence"), row.getBoolean("closed"), row.optString("fingerprint"))
                    lifecycles[life.digest] = life
                }
            } catch (failure: Exception) {
                enabled = false; entries.clear(); lifecycles.clear()
                healthy = false
                warning = "Private delivery storage could not be read (${failure.javaClass.simpleName}); delivery disabled. Reconfigure and clear before testing."
            }
        }
        if (failureMarker.exists()) {
            enabled = false; healthy = false
            warning = "Previous delivery storage failure; clear and reprovision before enabling"
        }
    }

    @Synchronized fun setEnabled(value: Boolean) {
        require(healthy || !value)
        enabled = value; generation++
        if (!value) {
            entries.values.filter { it.status == DeliveryStatus.PENDING || it.status == DeliveryStatus.INVALIDATION_PENDING }.forEach {
                it.status = DeliveryStatus.FAILED; it.note = "Cancelled by disable; never retried automatically"
            }
        }
        persist()
    }
    @Synchronized fun clear() {
        healthy = true; failureMarker.delete()
        enabled = false; generation++; entries.clear(); lifecycles.clear(); warning = ""
        secret = ByteArray(32).also { SecureRandom().nextBytes(it) }; persist()
    }
    @Synchronized fun rows(): List<QueueEntry> { expire(); return entries.values.map { it.copy() } }
    @Synchronized fun displayRows(): List<QueueEntry> = entries.values.map {
        if ((it.status == DeliveryStatus.PENDING || it.status == DeliveryStatus.INVALIDATION_PENDING || it.status == DeliveryStatus.FAILED) &&
            clock() - Instant.parse(it.candidate.observedAt).toEpochMilli() >= DELIVERY_TTL)
            it.copy(status = DeliveryStatus.EXPIRED, note = "Expired after 24 hours") else it.copy()
    }
    @Synchronized fun find(id: String): QueueEntry? { expire(); return entries[id]?.copy() }
    @Synchronized fun mark(id: String, status: DeliveryStatus, note: String, incrementAttempt: Boolean = false) {
        entries[id]?.let { it.status = status; it.note = note; if (incrementAttempt) it.attempts++ }; persist()
    }
    @Synchronized fun uploaded(id: String) {
        entries[id]?.let { it.uploaded = true; it.status = if (it.invalidate) DeliveryStatus.INVALIDATION_PENDING else DeliveryStatus.DELIVERED
            it.note = if (it.invalidate) "Invalidation pending" else "Delivered; unverified evidence" }; persist()
    }
    @Synchronized fun invalidationAttempt(id: String) { entries[id]?.let { it.invalidationAttempts++ }; persist() }
    @Synchronized fun retry(): List<String> {
        expire()
        val ids = entries.values.filter { it.status == DeliveryStatus.FAILED && it.note == "Retry limit reached" && it.manualRetries < 3 }
            .map { it.status = if (it.invalidate) DeliveryStatus.INVALIDATION_PENDING else DeliveryStatus.PENDING
                it.manualRetries++; it.attempts = 0; it.invalidationAttempts = 0; it.note = "Receiver requested retry"; it.candidate.eventId }
        persist(); return ids
    }
    @Synchronized fun synthetic(deviceId: String, amount: Long): Candidate? {
        if (!enabled || amount !in 1..1_000_000) return null
        return add(Candidate(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, deviceId,
            "SYNTHETIC", "synthetic.debug.v1", amount, Instant.ofEpochMilli(clock()).toString(), "SYNTHETIC"))
    }
    @Synchronized fun observe(key: String, postTime: Long, amount: Long, deviceId: String, changed: Boolean = false,
        contentDigest: String = "amount:$amount"): Candidate? {
        if (!enabled || key.length > 1024 || amount !in 1..1_000_000 || postTime <= 0) return null
        expire()
        if (clock() - postTime >= DELIVERY_TTL || postTime > clock() + 30_000) return null
        val digest = digest(key)
        val fingerprint = digest("content:$digest:$contentDigest")
        var life = lifecycles[digest]
        if (life != null && postTime < life.lastPostTime) return null
        // Key reuse is only accepted as a fresh lifecycle after observed removal and a newer post.
        // A missing removal deliberately retains the old lifecycle instead of inventing transaction identity.
        if (life == null || (life.closed && postTime > life.lastPostTime)) {
            if (lifecycles.size >= DELIVERY_CAPACITY && life == null) {
                warning = "Lifecycle capacity reached; clear deliberately before a new experiment"; persist(); return null
            }
            life = Lifecycle(digest, UUID.randomUUID().toString(), postTime, amount, 0, fingerprint = fingerprint)
            lifecycles[digest] = life
        } else {
            if (!life.closed && (changed || life.amount != amount || life.fingerprint != fingerprint)) invalidate(life)
            life.lastPostTime = maxOf(life.lastPostTime, postTime); persist(); return null
        }
        life.amount = amount; life.lastPostTime = postTime; life.sequence++
        return add(Candidate(UUID.randomUUID().toString(), life.notificationId, life.sequence, deviceId,
            "TNG_NOTIFICATION_CANDIDATE", "tng.receipt.en.observed.v1", amount,
            Instant.ofEpochMilli(postTime).toString(), "RULE_MATCHED"))
    }
    @Synchronized fun unknownUpdate(key: String, postTime: Long) {
        val life = lifecycles[digest(key)] ?: return
        if (postTime < life.lastPostTime) return
        life.lastPostTime = postTime
        if (life.closed) { persist(); return }
        warning = "A previously eligible notification changed to unsupported evidence. Review actual TNG history; uploaded evidence remains unverified."
        invalidate(life)
        persist()
    }
    private fun invalidate(life: Lifecycle) {
        warning = "Notification changed; original evidence is being invalidated. No second payment is inferred."
        entries.values.filter { it.candidate.notificationId == life.notificationId && it.status != DeliveryStatus.INVALIDATED &&
            it.status != DeliveryStatus.EXPIRED && (!it.note.startsWith("Cancelled by disable") || it.attempts > 0) }
            .forEach {
                if (it.note.startsWith("Cancelled by disable")) it.controlOnly = true
                it.invalidate = true; it.status = DeliveryStatus.INVALIDATION_PENDING; it.note = "Invalidation pending"
            }
    }
    @Synchronized fun removed(key: String, postTime: Long) {
        lifecycles[digest(key)]?.let { it.closed = true; it.lastPostTime = maxOf(postTime, it.lastPostTime) }; persist()
    }
    private fun add(candidate: Candidate): Candidate? {
        if (entries.size >= DELIVERY_CAPACITY) {
            warning = "Queue capacity reached; clear deliberately before a new experiment"; persist(); return null
        }
        entries[candidate.eventId] = QueueEntry(candidate); persist(); return candidate
    }
    private fun expire() {
        var changed = false
        entries.values.filter { it.status == DeliveryStatus.PENDING || it.status == DeliveryStatus.FAILED ||
            it.status == DeliveryStatus.INVALIDATION_PENDING }.forEach {
            if (clock() - Instant.parse(it.candidate.observedAt).toEpochMilli() >= DELIVERY_TTL) {
                if (it.status != DeliveryStatus.EXPIRED) { it.status = DeliveryStatus.EXPIRED; it.note = "Expired after 24 hours"; changed = true }
            }
        }
        if (changed) persist()
    }
    private fun digest(value: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private fun persist() {
        check(healthy) { "Delivery storage unavailable" }
        val root = JSONObject().put("secret", android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP))
            .put("enabled", enabled).put("generation", generation)
            .put("entries", JSONArray(entries.values.map { JSONObject().put("candidate", it.candidate.json())
                .put("status", it.status.name).put("attempts", it.attempts).put("note", it.note)
                .put("invalidate", it.invalidate).put("uploaded", it.uploaded).put("manualRetries", it.manualRetries)
                .put("invalidationAttempts", it.invalidationAttempts).put("controlOnly", it.controlOnly) }))
            .put("lifecycles", JSONArray(lifecycles.values.map { JSONObject().put("digest", it.digest)
                .put("notificationId", it.notificationId).put("postTime", it.lastPostTime)
                .put("amount", it.amount).put("sequence", it.sequence).put("closed", it.closed).put("fingerprint", it.fingerprint) }))
        val bytes = root.toString().toByteArray(Charsets.UTF_8); require(bytes.size <= 524_288)
        try {
            beforeWrite(); file.write(bytes)
        } catch (failure: Exception) {
            healthy = false; enabled = false
            warning = "Delivery persistence failed; transmission disabled and credentials revoked. Reprovision required."
            try { failureMarker.writeText("DISABLED") } catch (_: Exception) { }
            try { onFailure() } catch (_: Exception) { }
            throw failure
        }
    }
}
