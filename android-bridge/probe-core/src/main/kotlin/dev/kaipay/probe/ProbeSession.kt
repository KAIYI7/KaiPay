package dev.kaipay.probe

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class Delivery { NEW, DUPLICATE, UPDATE, OUT_OF_ORDER }
data class RedactedEvent(val delivery: Delivery, val diagnostic: Diagnostic)
data class Snapshot(val enabled: Boolean, val connected: Boolean, val events: List<RedactedEvent>)

/** Per-process, bounded, memory-only. Disable erases observations and rotates the HMAC key. */
class ProbeSession(private val capacity: Int = 32) {
    init { require(capacity in 1..128) }
    private var enabled = false
    private var connected = false
    private var secret = newSecret()
    private val lastSeen = linkedMapOf<String, Pair<Long, Diagnostic>>()
    private val events = ArrayDeque<RedactedEvent>()

    @Synchronized fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) clear()
    }
    @Synchronized fun setConnected(value: Boolean) {
        connected = value
        if (!value) { enabled = false; clear() }
    }
    @Synchronized fun clear() {
        lastSeen.clear(); events.clear(); secret = newSecret()
    }
    @Synchronized fun snapshot() = Snapshot(enabled, connected, events.toList())

    @Synchronized fun posted(packageName: String?, key: String?, postTime: Long, fields: () -> Fields) {
        if (!enabled || !connected || packageName != ProbeParser.OFFICIAL_PACKAGE) return
        if (key == null || key.length > 1024 || postTime < 0) return
        val diagnostic = ProbeParser.inspect(packageName, fields) ?: return
        val digest = digest(key)
        val previous = lastSeen[digest]
        val delivery = when {
            previous == null -> Delivery.NEW
            postTime < previous.first -> Delivery.OUT_OF_ORDER
            postTime == previous.first && diagnostic == previous.second -> Delivery.DUPLICATE
            else -> Delivery.UPDATE
        }
        if (delivery != Delivery.OUT_OF_ORDER) {
            lastSeen[digest] = postTime to diagnostic
            if (lastSeen.size > capacity) lastSeen.remove(lastSeen.keys.first())
        }
        if (events.size >= capacity) events.removeFirst()
        events.addLast(RedactedEvent(delivery, diagnostic))
    }

    private fun digest(key: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun newSecret() = ByteArray(32).also { SecureRandom().nextBytes(it) }
}
