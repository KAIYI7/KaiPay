package dev.kaipay.probe

import org.junit.Assert.*
import org.junit.Test

/** Invented inputs exercise local observation semantics, not actual provider wording. */
class ProbeSessionInspectionTest {
    private val official = ProbeParser.OFFICIAL_PACKAGE
    private fun active(clock: () -> Long = { 0L }, ttl: Long = 300_000L, capacity: Int = 32) =
        ProbeSession(capacity = capacity, ttlMillis = ttl, elapsedClock = clock).apply {
            setConnected(true); setEnabled(true)
        }
    private fun ProbeSession.post(text: String, time: Long = 100L, key: String = "invented-source",
        metadata: NotificationMetadata = NotificationMetadata()) =
        posted(official, key, time, metadata) { Fields(text = text) }

    @Test fun `same timestamp changed content is an update and identical content a duplicate`() {
        val session = active()
        session.post("Invented first body")
        session.post("Invented changed body")
        session.post("Invented changed body")
        val events = session.snapshot().events
        assertEquals(listOf(Delivery.NEW, Delivery.UPDATE, Delivery.DUPLICATE), events.map { it.delivery })
        assertEquals("Invented changed body", events.last().inspection!!.fields["text"])
        assertEquals(1, events.map { it.metadata!!.sourceAlias }.distinct().size)
    }

    @Test fun `supporting field and text line changes are updates even when main text is unchanged`() {
        val session = active()
        fun post(sub: String, line: String) = session.posted(official, "invented-source", 100L) {
            Fields(text = "Invented body", subText = sub, textLines = listOf(line))
        }
        post("Invented sub A", "Invented line A")
        post("Invented sub B", "Invented line A")
        post("Invented sub B", "Invented line B")
        assertEquals(listOf(Delivery.NEW, Delivery.UPDATE, Delivery.UPDATE), session.snapshot().events.map { it.delivery })
    }

    @Test fun `older callback never reads or redisplays stale content`() {
        val session = active()
        session.post("Invented newest", time = 200L)
        var reads = 0
        session.posted(official, "invented-source", 199L) { reads++; Fields(text = "Invented stale") }
        session.post("Invented newest", time = 200L)
        assertEquals(0, reads)
        val events = session.snapshot().events
        assertEquals(listOf(Delivery.NEW, Delivery.OUT_OF_ORDER, Delivery.DUPLICATE), events.map { it.delivery })
        assertNull(events[1].inspection)
        assertFalse(events.any { it.inspection?.fields?.values?.contains("Invented stale") == true })
    }

    @Test fun `removal clears all raw text and suppresses delayed callbacks for cleared sources`() {
        val session = active()
        session.post("Invented A", time = 100L, key = "invented-A")
        session.post("Invented B", time = 200L, key = "invented-B")
        session.removed(official, "invented-A", 100L)
        assertTrue(session.snapshot().events.isEmpty())
        assertEquals(1, session.snapshot().removals)
        listOf("invented-A" to 100L, "invented-B" to 199L, "invented-B" to 200L).forEach { (key, time) ->
            session.posted(official, key, time) { error("Removed raw content must not be read") }
        }
        assertTrue(session.snapshot().events.isEmpty())
        assertEquals(3, session.snapshot().ignoredLateCallbacks)
        session.post("Invented genuinely newer B", time = 201L, key = "invented-B")
        assertEquals("Invented genuinely newer B", session.snapshot().events.single().inspection!!.fields["text"])
    }

    @Test fun `unrelated removal neither clears text nor creates tombstones`() {
        val session = active()
        session.post("Invented body")
        session.removed("unrelated.synthetic", "invented-source", 100L)
        session.post("Invented body")
        assertEquals(listOf(Delivery.NEW, Delivery.DUPLICATE), session.snapshot().events.map { it.delivery })
        assertEquals(0, session.snapshot().removals)
    }

    @Test fun `monotonic expiry removes raw text exactly at TTL despite provider timestamp`() {
        var elapsed = 500L
        val session = active(clock = { elapsed }, ttl = 1_000L)
        session.post("Invented transient", time = Long.MAX_VALUE)
        elapsed = 1_499L
        assertEquals(1, session.snapshot().events.size)
        elapsed = 1_500L
        assertTrue(session.snapshot().events.isEmpty())
        // Expired deduplication state is deliberately bounded, not durable replay protection.
        session.post("Invented transient", time = Long.MAX_VALUE)
        assertEquals(Delivery.NEW, session.snapshot().events.single().delivery)
    }

    @Test fun `each raw observation expires independently without a new callback`() {
        var elapsed = 0L
        val session = active(clock = { elapsed }, ttl = 1_000L)
        session.post("Invented old", key = "invented-old")
        elapsed = 500L
        session.post("Invented recent", key = "invented-recent")
        elapsed = 1_000L
        val remaining = session.snapshot().events
        assertEquals(1, remaining.size)
        assertEquals("Invented recent", remaining.single().inspection!!.fields["text"])
        elapsed = 1_500L
        assertTrue(session.snapshot().events.isEmpty())
    }

    @Test fun `tombstones expire rather than promising permanent duplicate protection`() {
        var elapsed = 0L
        val session = active(clock = { elapsed }, ttl = 1_000L)
        session.post("Invented body")
        session.removed(official, "invented-source", 100L)
        elapsed = 999L
        session.posted(official, "invented-source", 100L) { error("Unexpired removal must gate reads") }
        elapsed = 1_000L
        session.post("Invented body")
        assertEquals(Delivery.NEW, session.snapshot().events.single().delivery)
    }

    @Test fun `disable erases diagnostics counters and gates supplier before reenable`() {
        val session = active()
        session.post("Invented sensitive local body")
        session.removed(official, "invented-source", 100L)
        session.setEnabled(false)
        session.posted(official, "invented-source", 101L) { error("Disabled inspection") }
        assertFalse(session.snapshot().enabled)
        assertTrue(session.snapshot().events.isEmpty())
        assertEquals(0, session.snapshot().removals)
        session.setEnabled(true)
        session.post("Invented fresh body")
        assertEquals(Delivery.NEW, session.snapshot().events.single().delivery)
    }

    @Test fun `connection loss erases raw text and reconnect requires explicit enable`() {
        val session = active()
        session.post("Invented local body")
        session.setConnected(false)
        val stopped = session.snapshot()
        assertFalse(stopped.enabled)
        assertFalse(stopped.connected)
        assertTrue(stopped.events.isEmpty())
        session.setConnected(true)
        session.posted(official, "invented-source", 100L) { error("Reconnect must not implicitly inspect") }
        session.setEnabled(true)
        session.post("Invented fresh body")
        assertEquals(Delivery.NEW, session.snapshot().events.single().delivery)
    }

    @Test fun `clear discards text while retaining explicit permission gates`() {
        val session = active()
        session.post("Invented old body")
        val oldAlias = session.snapshot().events.single().metadata!!.sourceAlias
        session.clear()
        assertTrue(session.snapshot().enabled)
        assertTrue(session.snapshot().connected)
        assertTrue(session.snapshot().events.isEmpty())
        session.post("Invented fresh body")
        assertNotEquals(oldAlias, session.snapshot().events.single().metadata!!.sourceAlias)
        assertEquals(Delivery.NEW, session.snapshot().events.single().delivery)
    }

    @Test fun `source and channel diagnostics never retain raw framework identifiers`() {
        val session = active()
        session.post("Invented body", key = "invented-private-key",
            metadata = NotificationMetadata("invented-private-channel", 77L, true, false))
        val metadata = session.snapshot().events.single().metadata!!
        assertEquals(77L, metadata.notificationTime)
        assertTrue(metadata.grouped)
        assertEquals(12, metadata.sourceAlias.length)
        assertEquals(12, metadata.channelAlias!!.length)
        assertFalse(session.snapshot().toString().contains("invented-private"))
        assertFalse(session.snapshot().toString().contains("Invented body"))
    }

    @Test fun `bounded recent diagnostics evict raw old observations`() {
        val session = active(capacity = 2)
        session.post("Invented A", key = "invented-A")
        session.post("Invented B", key = "invented-B")
        session.post("Invented C", key = "invented-C")
        assertEquals(listOf("Invented B", "Invented C"), session.snapshot().events.map { it.inspection!!.fields["text"] })
    }
}
