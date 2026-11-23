package dev.kaipay.probe

import org.junit.Assert.*
import org.junit.Test

class ProbeTest {
    private val official = ProbeParser.OFFICIAL_PACKAGE
    private fun active() = ProbeSession().apply { setEnabled(true); setConnected(true) }
    private fun ProbeSession.post(key: String = "synthetic-key", time: Long = 100, text: String = "synthetic") =
        posted(official, key, time) { Fields(text = text) }

    @Test fun `unsupported packages never read fields`() {
        listOf(null, "", "com.tngdigital.ewallet", "my.com.tngdigital.ewallet.fake", "unrelated").forEach {
            assertNull(ProbeParser.inspect(it) { error("Unrelated content read") })
        }
    }
    @Test fun `official package keeps presence only`() {
        val diagnostic = ProbeParser.inspect(official) { Fields("synthetic-title", "synthetic sender account", null) }!!
        assertEquals(Diagnostic(Disposition.UNKNOWN, true, true, false), diagnostic)
        assertFalse(diagnostic.toString().contains("synthetic"))
    }
    @Test fun `missing extras safely remain unknown`() {
        assertEquals(Diagnostic(Disposition.UNKNOWN), ProbeParser.inspect(official) { Fields() })
    }
    @Test fun `malformed field fails closed`() {
        assertEquals(Disposition.MALFORMED, ProbeParser.inspect(official) { Fields(42) }!!.disposition)
    }
    @Test fun `oversize fails closed without prefix scanning`() {
        assertEquals(Disposition.MALFORMED, ProbeParser.inspect(official) { Fields(text = "a".repeat(4097)) }!!.disposition)
    }
    @Test fun `credential markers drop all presence details`() {
        listOf("OTP 123456", "password example", "PIN 1234", "verification code", "kata laluan", "验证码").forEach {
            assertEquals(Diagnostic(Disposition.SENSITIVE_BLOCKED), ProbeParser.inspect(official) { Fields("title", it) })
        }
    }
    @Test fun `expanded secrets also blocked`() {
        assertEquals(Disposition.SENSITIVE_BLOCKED, ProbeParser.inspect(official) { Fields(bigText = "passcode") }!!.disposition)
    }
    @Test fun `all live wording remains unknown including synthetic grammar`() {
        listOf("SYNTHETIC_INCOMING MYR 12.34", "SYNTHETIC_OUTGOING MYR 12.34", "synthetic promotion").forEach {
            assertEquals(Disposition.UNKNOWN, ProbeParser.inspect(official) { Fields(text = it) }!!.disposition)
        }
    }
    @Test fun `synthetic amount preserves exact minor units`() {
        assertEquals(1234L, ProbeParser.parseSynthetic("SYNTHETIC_INCOMING MYR 12.34"))
        assertEquals(1L, ProbeParser.parseSynthetic("SYNTHETIC_INCOMING MYR 0.01"))
        assertEquals(99999999L, ProbeParser.parseSynthetic("SYNTHETIC_INCOMING MYR 999999.99"))
    }
    @Test fun `synthetic unsupported wording and imprecise amounts rejected`() {
        listOf(null, "", "SYNTHETIC_OUTGOING MYR 1.00", "SYNTHETIC_INCOMING MYR 1.001",
            "SYNTHETIC_INCOMING MYR -1.00", "SYNTHETIC_INCOMING MYR 0.00", "SYNTHETIC_INCOMING MYR 1e2",
            "SYNTHETIC_INCOMING MYR 1,000.00", "SYNTHETIC_INCOMING MYR 1.00 account=private").forEach {
            assertNull(ProbeParser.parseSynthetic(it))
        }
    }
    @Test fun `default off and disconnected never read fields`() {
        val session = ProbeSession()
        session.posted(official, "key", 100) { error("disabled read") }
        session.setEnabled(true)
        session.posted(official, "key", 100) { error("disconnected read") }
        assertTrue(session.snapshot().events.isEmpty())
    }
    @Test fun `unsupported session never reads key extras`() {
        active().posted("other", "key", 100) { error("unrelated read") }
    }
    @Test fun `duplicate and updated notifications differentiated`() {
        val session = active()
        session.post(); session.post(); session.post(time = 101)
        assertEquals(listOf(Delivery.NEW, Delivery.DUPLICATE, Delivery.UPDATE), session.snapshot().events.map { it.delivery })
    }
    @Test fun `delayed event does not replace newer metadata`() {
        val session = active()
        session.post(time = 200); session.post(time = 100); session.post(time = 200)
        assertEquals(listOf(Delivery.NEW, Delivery.OUT_OF_ORDER, Delivery.DUPLICATE), session.snapshot().events.map { it.delivery })
    }
    @Test fun `disable erases all observations and prevents extraction`() {
        val session = active(); session.post(); session.setEnabled(false)
        assertTrue(session.snapshot().events.isEmpty())
        session.posted(official, "key", 100) { error("disabled read") }
        session.setEnabled(true); session.post()
        assertEquals(Delivery.NEW, session.snapshot().events.single().delivery)
    }
    @Test fun `memory and keys bounded and diagnostic has no private identifiers`() {
        val session = active()
        repeat(100) { session.post(key = "private-wallet-$it", time = it.toLong(), text = "private sender") }
        val result = session.snapshot()
        assertEquals(32, result.events.size)
        assertFalse(result.toString().contains("private"))
    }
    @Test fun `invalid metadata never reads fields`() {
        val session = active()
        session.posted(official, null, 100) { error("null key") }
        session.posted(official, "k".repeat(1025), 100) { error("oversize key") }
        session.posted(official, "key", -1) { error("invalid time") }
        assertTrue(session.snapshot().events.isEmpty())
    }
    @Test fun `connection loss prevents extraction`() {
        val session = active(); session.setConnected(false)
        session.posted(official, "key", 100) { error("disconnected read") }
        assertTrue(session.snapshot().events.isEmpty())
    }
    @Test fun `disconnect erases observations and reconnect requires explicit enable`() {
        val session = active(); session.post()
        assertEquals(1, session.snapshot().events.size)
        session.setConnected(false)
        assertFalse(session.snapshot().enabled)
        assertTrue(session.snapshot().events.isEmpty())
        session.setConnected(true)
        session.posted(official, "key", 100) { error("Regrant must not silently enable") }
        assertTrue(session.snapshot().events.isEmpty())
        session.setEnabled(true); session.post()
        assertEquals(Delivery.NEW, session.snapshot().events.single().delivery)
    }
}
