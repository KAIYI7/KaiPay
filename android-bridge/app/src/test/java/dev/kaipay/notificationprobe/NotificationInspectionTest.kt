package dev.kaipay.notificationprobe

import android.app.Notification
import android.os.Bundle
import android.text.SpannableString
import dev.kaipay.probe.ClassificationOutcome
import dev.kaipay.probe.Delivery
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

/** Invented extras through framework callbacks; these tests do not assert actual TNG formats. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NotificationInspectionTest {
    private lateinit var controller: ServiceController<TngNotificationListener>
    private lateinit var listener: TngNotificationListener
    private val marker = "Invented locally inspected content"

    @Before fun setUp() {
        ProbeRuntime.disable()
        ProbeRuntime.session.setConnected(false)
        controller = Robolectric.buildService(TngNotificationListener::class.java).create()
        listener = controller.get()
        grantAccess(listener)
        listener.onListenerConnected()
        ProbeRuntime.session.setEnabled(true)
        ShadowLog.clear()
    }

    @After fun tearDown() {
        controller.destroy()
        assertFalse("Raw synthetic content must not enter Android logs", ShadowLog.getLogs().any {
            it.msg.contains(marker) || it.throwable?.toString()?.contains(marker) == true
        })
        assertTrue(ProbeRuntime.session.snapshot().events.isEmpty())
    }

    private fun events() = ProbeRuntime.session.snapshot().events
    private fun post(extras: Bundle, id: Int = 1, time: Long = 100L) =
        listener.onNotificationPosted(syntheticNotification(id = id, time = time, extras = extras))

    @Test fun `six Android extra families retain supported styled text locally`() {
        post(Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, SpannableString("Invented title"))
            putCharSequence(Notification.EXTRA_TEXT, StringBuilder(marker))
            putCharSequence(Notification.EXTRA_BIG_TEXT, SpannableString("Invented expanded RM 12.34"))
            putCharSequence(Notification.EXTRA_SUB_TEXT, StringBuilder("Invented subtext"))
            putCharSequence(Notification.EXTRA_SUMMARY_TEXT, SpannableString("Invented summary"))
            putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf(SpannableString("Invented line A"),
                StringBuilder("Invented line B")))
        })
        val inspection = events().single().inspection!!
        assertEquals(mapOf("title" to "Invented title", "text" to marker,
            "bigText" to "Invented expanded RM 12.34", "subText" to "Invented subtext",
            "summaryText" to "Invented summary"), inspection.fields)
        assertEquals(listOf("Invented line A", "Invented line B"), inspection.textLines)
        assertEquals(1234L, inspection.classification.amountMinor)
        assertEquals(ClassificationOutcome.UNKNOWN, inspection.classification.outcome)
        assertFalse(ProbeRuntime.session.snapshot().toString().contains(marker))
    }

    @Test fun `credentials in each supporting field suppress every raw field`() {
        listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT, Notification.EXTRA_TEXT_LINES).forEachIndexed { id, field ->
            post(Bundle().apply {
                putString(Notification.EXTRA_TEXT, marker)
                if (field == Notification.EXTRA_TEXT_LINES) putCharSequenceArray(field, arrayOf("Invented PIN 1234"))
                else putCharSequence(field, SpannableString("Invented OTP 123456"))
            }, id = id)
        }
        assertEquals(3, events().size)
        events().forEach {
            val inspection = it.inspection!!
            assertEquals(Disposition.SENSITIVE_BLOCKED, it.diagnostic.disposition)
            assertTrue(inspection.fields.isEmpty())
            assertTrue(inspection.textLines.isEmpty())
            assertEquals(ClassificationOutcome.UNKNOWN, inspection.classification.outcome)
        }
    }

    @Test fun `non string supporting fields fail closed including already readable main text`() {
        listOf(Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT).forEachIndexed { id, field ->
            post(Bundle().apply { putString(Notification.EXTRA_TEXT, marker); putInt(field, 42) }, id = id)
        }
        post(Bundle().apply {
            putString(Notification.EXTRA_TEXT, marker)
            putSerializable(Notification.EXTRA_TEXT_LINES, arrayOf(42))
        }, id = 3)
        assertEquals(3, events().size)
        events().forEach {
            val inspection = it.inspection!!
            assertEquals(Disposition.MALFORMED, it.diagnostic.disposition)
            assertTrue(inspection.fields.isEmpty())
            assertTrue(inspection.textLines.isEmpty())
        }
    }

    @Test fun `oversized styled supporting text is not truncated into retained content`() {
        post(Bundle().apply {
            putString(Notification.EXTRA_TEXT, marker)
            putCharSequence(Notification.EXTRA_SUB_TEXT, SpannableString("x".repeat(4097)))
        })
        assertEquals(Disposition.MALFORMED, events().single().diagnostic.disposition)
        assertTrue(events().single().inspection!!.fields.isEmpty())
    }

    @Test fun `too many text lines rejected before any line content conversion`() {
        val lines = Array(17) { CountingSequence(marker, failOnRead = true) }
        post(Bundle().apply { putCharSequenceArray(Notification.EXTRA_TEXT_LINES, lines) })
        assertTrue(lines.all { it.reads == 0 })
        assertEquals(Disposition.MALFORMED, events().single().diagnostic.disposition)
        assertTrue(events().single().inspection!!.textLines.isEmpty())
    }

    @Test fun `unrelated source does not inspect supporting fields or text lines`() {
        val content = CountingSequence(marker, failOnRead = true)
        listener.onNotificationPosted(syntheticNotification(packageName = "unrelated.synthetic", extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_SUB_TEXT, content)
            putCharSequence(Notification.EXTRA_SUMMARY_TEXT, content)
            putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf(content))
        }))
        assertEquals(0, content.reads)
        assertTrue(events().isEmpty())
    }

    @Test fun `group summaries remain unknown and explain lack of individual receipt identity`() {
        val notification = syntheticNotification(extras = Bundle().apply {
            putString(Notification.EXTRA_TEXT, "Invented receipt RM 12.34")
        })
        notification.notification.flags = notification.notification.flags or Notification.FLAG_GROUP_SUMMARY
        listener.onNotificationPosted(notification)
        val event = events().single()
        val classification = event.inspection!!.classification
        assertTrue(event.metadata!!.groupSummary)
        assertEquals(ClassificationOutcome.UNKNOWN, classification.outcome)
        assertTrue(classification.explanation.contains("Grouped summary"))
    }

    @Test fun `same timestamp supporting text update is distinct from duplicate callback`() {
        fun extras(summary: String) = Bundle().apply {
            putString(Notification.EXTRA_TEXT, marker)
            putString(Notification.EXTRA_SUMMARY_TEXT, summary)
        }
        post(extras("Invented first summary"))
        post(extras("Invented second summary"))
        post(extras("Invented second summary"))
        assertEquals(listOf(Delivery.NEW, Delivery.UPDATE, Delivery.DUPLICATE), events().map { it.delivery })
        assertEquals("Invented second summary", events().last().inspection!!.fields["summaryText"])
    }

    @Test fun `removed notification late callback cannot restore or inspect raw text`() {
        post(Bundle().apply { putString(Notification.EXTRA_TEXT, marker) })
        listener.onNotificationRemoved(syntheticNotification())
        val content = CountingSequence(marker, failOnRead = true)
        post(Bundle().apply { putCharSequence(Notification.EXTRA_TEXT, content) })
        assertEquals(0, content.reads)
        assertTrue(events().isEmpty())
        assertEquals(1, ProbeRuntime.session.snapshot().removals)
        assertEquals(1, ProbeRuntime.session.snapshot().ignoredLateCallbacks)
    }

    @Test @Config(sdk = [26]) fun `minimum API supports all field families and revocation erases content`() {
        post(Bundle().apply {
            putString(Notification.EXTRA_TITLE, "Invented title")
            putString(Notification.EXTRA_TEXT, marker)
            putString(Notification.EXTRA_BIG_TEXT, "Invented expanded")
            putString(Notification.EXTRA_SUB_TEXT, "Invented sub")
            putString(Notification.EXTRA_SUMMARY_TEXT, "Invented summary")
            putCharSequenceArray(Notification.EXTRA_TEXT_LINES, arrayOf("Invented line"))
        })
        assertEquals(5, events().single().inspection!!.fields.size)
        assertEquals(listOf("Invented line"), events().single().inspection!!.textLines)
        grantAccess(listener, false)
        val content = CountingSequence(marker, failOnRead = true)
        post(Bundle().apply { putCharSequence(Notification.EXTRA_TEXT, content) }, id = 2)
        assertEquals(0, content.reads)
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertTrue(events().isEmpty())
    }
}
