package dev.kaipay.notificationprobe

import android.app.Notification
import android.os.Bundle
import dev.kaipay.probe.ClassificationOutcome
import dev.kaipay.probe.EvidenceQuality
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

/** Only the generic wording is observed; fixture party and amount are invented. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ObservedProfileAdapterTest {
    private lateinit var controller: ServiceController<TngNotificationListener>
    private lateinit var listener: TngNotificationListener
    private val inventedBody = "Alex Demo has transferred RM12.34 to you. Tap here to check the transaction details."

    @Before fun setup() {
        ProbeRuntime.disable()
        controller = Robolectric.buildService(TngNotificationListener::class.java).create()
        listener = controller.get()
        grantAccess(listener)
        listener.onListenerConnected()
        ProbeRuntime.session.setEnabled(true)
        ShadowLog.clear()
    }
    @After fun cleanup() {
        controller.destroy()
        assertFalse(ShadowLog.getLogs().any { it.msg.contains(inventedBody) })
    }
    private fun post(title: String = "You\u2019ve received money!") = listener.onNotificationPosted(
        syntheticNotification(extras = Bundle().apply {
            putString(Notification.EXTRA_TITLE, title)
            putString(Notification.EXTRA_TEXT, inventedBody)
            putString(Notification.EXTRA_BIG_TEXT, inventedBody)
        }))

    @Test fun `framework adapter applies reviewed profile without turning candidate into settlement`() {
        post()
        val result = ProbeRuntime.session.snapshot().events.single().inspection!!.classification
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE, result.outcome)
        assertEquals(1234L, result.amountMinor)
        assertEquals(EvidenceQuality.RULE_MATCHED, result.confidence)
        assertNull(result.reference)
        assertTrue(result.explanation.contains("independent transaction verification"))
        assertFalse(ProbeRuntime.session.snapshot().toString().contains(inventedBody))
    }
    @Test fun `unobserved title remains unknown and revocation clears every candidate`() {
        post("You've received money!")
        assertEquals(ClassificationOutcome.UNKNOWN,
            ProbeRuntime.session.snapshot().events.single().inspection!!.classification.outcome)
        ProbeRuntime.session.clear()
        post()
        assertEquals(ClassificationOutcome.INCOMING_CANDIDATE,
            ProbeRuntime.session.snapshot().events.single().inspection!!.classification.outcome)
        grantAccess(listener, false)
        post()
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertTrue(ProbeRuntime.session.snapshot().events.isEmpty())
    }
}
