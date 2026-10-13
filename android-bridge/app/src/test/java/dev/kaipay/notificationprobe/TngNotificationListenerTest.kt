package dev.kaipay.notificationprobe

import android.app.Notification
import android.os.Bundle
import android.text.SpannableString
import dev.kaipay.probe.Delivery
import dev.kaipay.probe.Diagnostic
import dev.kaipay.probe.Disposition
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowLog

/** Direct framework callbacks under JVM shadows do not prove OS delivery or TNG compatibility. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
// Test-only Oracle JDK JCE interoperability: retain real JDK crypto without native Conscrypt.
// Device/OEM crypto behavior still requires physical-phone verification.
@ConscryptMode(ConscryptMode.Mode.OFF)
class TngNotificationListenerTest {
    private lateinit var controller: ServiceController<TngNotificationListener>
    private lateinit var listener: TngNotificationListener

    @Before fun setUp() {
        ProbeRuntime.session.setConnected(false)
        controller = Robolectric.buildService(TngNotificationListener::class.java).create()
        listener = controller.get()
        grantAccess(listener)
        ShadowLog.clear()
    }

    @After fun tearDown() {
        controller.destroy()
        assertFalse("Synthetic content leaked to Android log", ShadowLog.getLogs().any {
            it.msg.contains(SYNTHETIC_PRIVATE) || it.throwable?.toString()?.contains(SYNTHETIC_PRIVATE) == true
        })
    }

    private fun enable() { listener.onListenerConnected(); ProbeRuntime.session.setEnabled(true) }
    private fun events() = ProbeRuntime.session.snapshot().events

    @Test fun `unsupported source discarded before reading any content`() {
        enable()
        listOf("unrelated.synthetic", "my.com.tngdigital.ewallet.fake", "com.tngdigital.ewallet").forEach {
            val content = CountingSequence(SYNTHETIC_PRIVATE, failOnRead = true)
            listener.onNotificationPosted(syntheticNotification(packageName = it,
                extras = Bundle().apply { putCharSequence(Notification.EXTRA_TEXT, content) }))
            assertEquals(0, content.reads)
        }
        listener.onNotificationPosted(null)
        assertTrue(events().isEmpty())
    }

    @Test fun `disabled or disconnected listener does not read supported source content`() {
        val content = CountingSequence(SYNTHETIC_PRIVATE, failOnRead = true)
        val event = syntheticNotification(extras = Bundle().apply { putCharSequence(Notification.EXTRA_TEXT, content) })
        listener.onNotificationPosted(event)
        listener.onListenerConnected()
        listener.onNotificationPosted(event)
        assertEquals(0, content.reads)
        assertTrue(events().isEmpty())
    }

    @Test fun `Android styled text retained locally but default object rendering stays redacted`() {
        enable()
        listener.onNotificationPosted(syntheticNotification(extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, SpannableString(SYNTHETIC_PRIVATE))
            putCharSequence(Notification.EXTRA_TEXT, StringBuilder(SYNTHETIC_PRIVATE))
        }))
        assertEquals(Diagnostic(Disposition.UNKNOWN, true, true, false), events().single().diagnostic)
        assertEquals(SYNTHETIC_PRIVATE, events().single().inspection?.fields?.get("text"))
        assertFalse(ProbeRuntime.session.snapshot().toString().contains(SYNTHETIC_PRIVATE))
    }

    @Test fun `missing and malformed Android extras fail conservatively`() {
        enable()
        listener.onNotificationPosted(syntheticNotification(extras = null))
        listener.onNotificationPosted(syntheticNotification(id = 2, extras = Bundle().apply {
            putInt(Notification.EXTRA_TEXT, 42)
        }))
        listener.onNotificationPosted(syntheticNotification(id = 3, extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TEXT, SpannableString("x".repeat(4097)))
        }))
        assertEquals(listOf(Disposition.UNKNOWN, Disposition.MALFORMED, Disposition.MALFORMED),
            events().map { it.diagnostic.disposition })
        assertEquals(Diagnostic(Disposition.UNKNOWN), events().first().diagnostic)
    }

    @Test fun `broken synthetic CharSequence dropped without content or exception logging`() {
        enable()
        val content = CountingSequence(SYNTHETIC_PRIVATE, failOnRead = true)
        listener.onNotificationPosted(syntheticNotification(extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TEXT, content)
        }))
        assertTrue(content.reads > 0)
        assertEquals(Disposition.MALFORMED, events().single().diagnostic.disposition)
        assertTrue(events().single().inspection!!.fields.isEmpty())
        assertTrue(ShadowLog.getLogs().isEmpty())
    }

    @Test fun `synthetic credential markers block every presence field`() {
        enable()
        listener.onNotificationPosted(syntheticNotification(extras = Bundle().apply {
            putString(Notification.EXTRA_TITLE, SYNTHETIC_PRIVATE)
            putCharSequence(Notification.EXTRA_BIG_TEXT, SpannableString("SYNTHETIC OTP 000000"))
        }))
        assertEquals(Diagnostic(Disposition.SENSITIVE_BLOCKED), events().single().diagnostic)
    }

    @Test fun `incoming outgoing and promotion synthetic wording never establishes direction`() {
        enable()
        listOf("SYNTHETIC_INCOMING MYR 12.34", "SYNTHETIC_OUTGOING MYR 12.34", "SYNTHETIC_PROMOTION").forEachIndexed { id, text ->
            listener.onNotificationPosted(syntheticNotification(id = id, extras = Bundle().apply {
                putString(Notification.EXTRA_TEXT, text)
            }))
        }
        assertEquals(3, events().size)
        assertTrue(events().all { it.diagnostic == Diagnostic(Disposition.UNKNOWN, textPresent = true) })
    }

    @Test fun `framework duplicate update and delayed callbacks classified without replacing newest`() {
        enable()
        listOf(200L, 200L, 201L, 100L, 201L).forEach { listener.onNotificationPosted(syntheticNotification(time = it)) }
        assertEquals(listOf(Delivery.NEW, Delivery.DUPLICATE, Delivery.UPDATE, Delivery.OUT_OF_ORDER, Delivery.DUPLICATE),
            events().map { it.delivery })
    }

    @Test fun `unsupported removal leaves diagnostics but supported removal clears all`() {
        enable()
        listener.onNotificationPosted(syntheticNotification())
        listener.onNotificationRemoved(null)
        listener.onNotificationRemoved(syntheticNotification(packageName = "unrelated.synthetic"))
        assertEquals(1, events().size)
        listener.onNotificationRemoved(syntheticNotification())
        assertTrue(events().isEmpty())
    }

    @Test fun `revoked access clears data without reading content and regrant requires enable`() {
        enable()
        listener.onNotificationPosted(syntheticNotification())
        grantAccess(listener, false)
        val content = CountingSequence(SYNTHETIC_PRIVATE, failOnRead = true)
        listener.onNotificationPosted(syntheticNotification(extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TEXT, content)
        }))
        assertEquals(0, content.reads)
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertTrue(events().isEmpty())
        grantAccess(listener)
        listener.onNotificationPosted(syntheticNotification())
        assertTrue(events().isEmpty())
        ProbeRuntime.session.setEnabled(true)
        listener.onNotificationPosted(syntheticNotification())
        assertEquals(Delivery.NEW, events().single().delivery)
    }

    @Test fun `disconnect reconnect and destruction keep enable fail closed`() {
        enable()
        listener.onNotificationPosted(syntheticNotification())
        listener.onListenerDisconnected()
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertFalse(ProbeRuntime.session.snapshot().connected)
        assertTrue(events().isEmpty())
        listener.onListenerConnected()
        listener.onNotificationPosted(syntheticNotification())
        assertTrue(events().isEmpty())
        ProbeRuntime.session.setEnabled(true)
        listener.onNotificationPosted(syntheticNotification())
        listener.onDestroy()
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertFalse(ProbeRuntime.session.snapshot().connected)
        assertTrue(events().isEmpty())
    }

    @Test @Config(sdk = [26]) fun `API26 legacy access allows callback and revocation clears diagnostics`() {
        enable()
        listener.onNotificationPosted(syntheticNotification())
        assertEquals(1, events().size)
        grantAccess(listener, false)
        listener.onNotificationPosted(syntheticNotification())
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertTrue(events().isEmpty())
    }
}
