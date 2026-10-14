package dev.kaipay.probe

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class Delivery { NEW, DUPLICATE, UPDATE, OUT_OF_ORDER }
data class NotificationMetadata(
    val channelId: String? = null,
    val notificationTime: Long? = null,
    val grouped: Boolean = false,
    val groupSummary: Boolean = false
)
data class EventMetadata(
    val sourceAlias: String,
    val postTime: Long,
    val notificationTime: Long?,
    val channelAlias: String?,
    val grouped: Boolean,
    val groupSummary: Boolean
)
data class RedactedEvent(
    val delivery: Delivery,
    val diagnostic: Diagnostic,
    val inspection: ContentInspection? = null,
    val metadata: EventMetadata? = null,
    internal val receivedElapsed: Long = 0
)
data class Snapshot(
    val enabled: Boolean, val connected: Boolean, val events: List<RedactedEvent>,
    val removals: Int = 0, val ignoredLateCallbacks: Int = 0
)

/** Bounded local text inspection. No disk/network, raw Android keys or implicit re-enable. */
class ProbeSession(
    private val capacity: Int = 32,
    private val ttlMillis: Long = 300_000,
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val profile: ClassificationProfile = ClassificationProfile.EMPTY
) {
    init { require(capacity in 1..128); require(ttlMillis in 1..300_000) }
    private data class Seen(val postTime: Long, val fingerprint: String, val receivedElapsed: Long)
    private var enabled = false
    private var connected = false
    private var secret = newSecret()
    private val lastSeen = linkedMapOf<String, Seen>()
    private val removedSources = linkedMapOf<String, Pair<Long, Long>>()
    private val events = ArrayDeque<RedactedEvent>()
    private var removals = 0
    private var ignoredLate = 0

    @Synchronized fun setEnabled(value: Boolean) { enabled = value; if (!value) clear() }
    @Synchronized fun setConnected(value: Boolean) {
        connected = value
        if (!value) { enabled = false; clear() }
    }
    @Synchronized fun clear() {
        lastSeen.clear(); removedSources.clear(); events.clear(); secret = newSecret()
        removals = 0; ignoredLate = 0
    }
    @Synchronized fun snapshot(): Snapshot {
        expire()
        return Snapshot(enabled, connected, events.toList(), removals, ignoredLate)
    }

    @Synchronized fun posted(
        packageName: String?, key: String?, postTime: Long,
        metadata: NotificationMetadata = NotificationMetadata(), fields: () -> Fields
    ): RedactedEvent? {
        if (!enabled || !connected || packageName != ProbeParser.OFFICIAL_PACKAGE) return null
        if (key == null || key.length > 1024 || postTime < 0) return null
        expire()
        val now = elapsedClock()
        val source = digest(key)
        if (removedSources[source]?.let { postTime <= it.first } == true) {
            ignoredLate = (ignoredLate + 1).coerceAtMost(1_000_000)
            return null // Never read/restore the content of a removed notification's delayed callback.
        }
        val previous = lastSeen[source]
        val safeMetadata = EventMetadata(source.take(12), postTime,
            metadata.notificationTime?.takeIf { it >= 0 },
            metadata.channelId?.takeIf { it.length <= 128 }?.let { digest("channel:$it").take(12) },
            metadata.grouped, metadata.groupSummary)
        if (previous != null && postTime < previous.postTime) {
            val event = RedactedEvent(Delivery.OUT_OF_ORDER, Diagnostic(Disposition.UNKNOWN),
                metadata = safeMetadata, receivedElapsed = now)
            append(event)
            return event // Old callbacks are metadata-only and cannot replace or redisplay newer text.
        }
        val inspection = ProbeParser.inspectContent(packageName, fields, metadata.groupSummary, profile) ?: return null
        val fingerprint = digest(buildString {
            append(inspection.diagnostic); append(safeMetadata.channelAlias)
            append(metadata.grouped); append(metadata.groupSummary); append(safeMetadata.notificationTime)
            inspection.fields.toSortedMap().forEach { (name, value) ->
                append(name.length); append(':'); append(name); append(value.length); append(':'); append(value)
            }
            inspection.textLines.forEach { append(it.length); append(':'); append(it) }
        })
        val delivery = when {
            previous == null -> Delivery.NEW
            postTime == previous.postTime && fingerprint == previous.fingerprint -> Delivery.DUPLICATE
            else -> Delivery.UPDATE
        }
        lastSeen[source] = Seen(postTime, fingerprint, now)
        if (lastSeen.size > capacity) lastSeen.remove(lastSeen.keys.first())
        removedSources.remove(source)
        val event = RedactedEvent(delivery, inspection.diagnostic, inspection, safeMetadata, now)
        append(event)
        return event
    }

    @Synchronized fun removed(packageName: String?, key: String?, postTime: Long) {
        if (packageName != ProbeParser.OFFICIAL_PACKAGE || !enabled || !connected) return
        expire()
        val now = elapsedClock()
        // Preserve B1.5's removal-clears-all behavior; retain only bounded HMAC tombstones.
        lastSeen.forEach { (source, seen) -> removedSources[source] = seen.postTime to now }
        if (key != null && key.length <= 1024 && postTime >= 0) {
            val source = digest(key)
            removedSources[source] = maxOf(postTime, removedSources[source]?.first ?: 0) to now
        }
        while (removedSources.size > capacity) removedSources.remove(removedSources.keys.first())
        lastSeen.clear(); events.clear()
        removals = (removals + 1).coerceAtMost(1_000_000)
    }

    private fun append(event: RedactedEvent) {
        if (events.size >= capacity) events.removeFirst()
        events.addLast(event)
    }
    private fun expire() {
        val now = elapsedClock()
        events.removeAll { now - it.receivedElapsed >= ttlMillis }
        lastSeen.entries.removeAll { now - it.value.receivedElapsed >= ttlMillis }
        removedSources.entries.removeAll { now - it.value.second >= ttlMillis }
    }
    private fun digest(value: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun newSecret() = ByteArray(32).also { SecureRandom().nextBytes(it) }
}
