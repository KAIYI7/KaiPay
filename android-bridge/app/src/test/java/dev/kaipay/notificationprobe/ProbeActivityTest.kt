package dev.kaipay.notificationprobe

import android.provider.Settings
import android.graphics.Insets
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import android.widget.ScrollView
import dev.kaipay.probe.ProbeParser
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowNotificationListenerService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
// Test-only Oracle JDK JCE interoperability; production crypto/provider selection is unchanged.
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProbeActivityTest {
    private lateinit var controller: ActivityController<ProbeActivity>
    private lateinit var activity: ProbeActivity

    @Before fun setUp() {
        ProbeRuntime.session.setConnected(false)
        grantAccess(granted = false)
        controller = Robolectric.buildActivity(ProbeActivity::class.java).setup()
        activity = controller.get()
    }

    @After fun tearDown() { controller.pause().stop().destroy(); ProbeRuntime.session.setConnected(false) }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun views() = descendants(activity.findViewById(android.R.id.content))
    private fun click(label: String) { assertTrue(views().filterIsInstance<Button>().single { it.text.toString() == label }.performClick()) }
    private fun uiText() = views().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }

    @Test fun `startup default off visible uncertainty and screenshot protection`() {
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertTrue(uiText().contains("TNG compatibility UNVERIFIED"))
        assertTrue(uiText().contains("OS access: false"))
        assertTrue(uiText().contains("Detection enabled: false"))
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun `enable with no OS permission does not activate or request rebind`() {
        click("Enable local detection")
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertEquals(0, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test fun `explicit enable after grant requests reconnect only when disconnected`() {
        grantAccess(activity)
        click("Enable local detection")
        assertTrue(ProbeRuntime.session.snapshot().enabled)
        assertEquals(1, ShadowNotificationListenerService.getRebindRequestCount())
        ProbeRuntime.session.setConnected(true)
        click("Enable local detection")
        assertEquals(1, ShadowNotificationListenerService.getRebindRequestCount())
        assertTrue(uiText().contains("Listener connected: true"))
    }

    @Test fun `redacted UI omits transient contents and disable clears diagnostics`() {
        grantAccess(activity)
        ProbeRuntime.session.setConnected(true)
        click("Enable local detection")
        val listener = Robolectric.buildService(TngNotificationListener::class.java).create()
        try {
            listener.get().onNotificationPosted(syntheticNotification())
            click("Refresh status")
            assertTrue(uiText().contains("Redacted events: 1 / 32"))
            assertTrue(uiText().contains("UNKNOWN"))
            assertFalse(uiText().contains(SYNTHETIC_PRIVATE))
            assertFalse(uiText().contains("SYNTHETIC_TAG"))
            assertFalse(uiText().contains(ProbeParser.OFFICIAL_PACKAGE))
            click("Disable and clear")
            assertFalse(ProbeRuntime.session.snapshot().enabled)
            assertTrue(ProbeRuntime.session.snapshot().events.isEmpty())
            assertTrue(uiText().contains("Redacted events: 0 / 32"))
        } finally { listener.destroy() }
    }

    @Test fun `resume after revoke clears diagnostics and regrant does not auto enable`() {
        grantAccess(activity)
        ProbeRuntime.session.setConnected(true)
        click("Enable local detection")
        ProbeRuntime.session.posted(ProbeParser.OFFICIAL_PACKAGE, "SYNTHETIC_KEY", 100) { dev.kaipay.probe.Fields(text = SYNTHETIC_PRIVATE) }
        assertEquals(1, ProbeRuntime.session.snapshot().events.size)
        controller.pause()
        grantAccess(activity, false)
        controller.resume()
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertTrue(ProbeRuntime.session.snapshot().events.isEmpty())
        assertTrue(uiText().contains("OS access: false"))
        controller.pause()
        grantAccess(activity)
        controller.resume()
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertTrue(uiText().contains("Detection enabled: false"))
    }

    @Test fun `activity recreation preserves process gate but does not grant access`() {
        grantAccess(activity)
        ProbeRuntime.session.setConnected(true)
        click("Enable local detection")
        controller.recreate()
        activity = controller.get()
        assertTrue(ProbeRuntime.session.snapshot().enabled)
        assertTrue(uiText().contains("Detection enabled: true"))
        // Simulate a fresh process session explicitly; activity restart alone is not process death.
        ProbeRuntime.session.setConnected(false)
        controller.recreate()
        activity = controller.get()
        assertFalse(ProbeRuntime.session.snapshot().enabled)
        assertTrue(uiText().contains("Detection enabled: false"))
    }

    @Test fun `notification settings control opens the Android permission screen`() {
        click("Open notification access settings")
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertFalse(ProbeRuntime.session.snapshot().enabled)
    }

    @Test fun `missing notification settings screen yields visible non sensitive diagnostic`() {
        // Synthetic environment has no settings activity. Enforce intent resolution like the OS.
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        click("Open notification access settings")
        assertTrue(uiText().contains("Notification settings unavailable on this device."))
        click("Refresh status")
        assertTrue(uiText().contains("Notification settings unavailable on this device."))
        assertFalse(ProbeRuntime.session.snapshot().enabled)
    }

    private fun scroll() = views().filterIsInstance<ScrollView>().single()
    private fun assertPadding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        assertEquals(listOf(left, top, right, bottom),
            listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
    }

    @Test fun `API35 bars and cutout protect controls without accumulating padding`() {
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), Insets.of(4, 24, 6, 48))
            .setInsets(WindowInsets.Type.displayCutout(), Insets.of(12, 36, 10, 0))
            .build()
        val view = scroll()
        view.dispatchApplyWindowInsets(insets)
        assertPadding(view, 12, 36, 10, 48)
        view.dispatchApplyWindowInsets(insets)
        assertPadding(view, 12, 36, 10, 48)
        assertTrue(uiText().contains("TNG compatibility UNVERIFIED"))
        assertFalse(ProbeRuntime.session.snapshot().enabled)
    }

    @Test fun `API35 changed and zero insets replace earlier padding`() {
        val view = scroll()
        view.dispatchApplyWindowInsets(WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 48)).build())
        assertPadding(view, 0, 24, 0, 48)
        view.dispatchApplyWindowInsets(WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), Insets.of(48, 0, 0, 0)).build())
        assertPadding(view, 48, 0, 0, 0)
        view.dispatchApplyWindowInsets(WindowInsets.Builder().build())
        assertPadding(view, 0, 0, 0, 0)
    }

    @Suppress("DEPRECATION")
    @Test @Config(sdk = [26]) fun `API26 public legacy insets protect controls and refresh idempotently`() {
        val view = scroll()
        // API26 has no public Rect constructor/builder; copy actual framework insets.
        val source = checkNotNull(activity.window.decorView.rootWindowInsets)
        val insets = WindowInsets(source).replaceSystemWindowInsets(Rect(3, 20, 5, 40))
        view.dispatchApplyWindowInsets(insets)
        assertPadding(view, 3, 20, 5, 40)
        view.dispatchApplyWindowInsets(insets)
        assertPadding(view, 3, 20, 5, 40)
        view.dispatchApplyWindowInsets(WindowInsets(source).replaceSystemWindowInsets(Rect()))
        assertPadding(view, 0, 0, 0, 0)
        click("Enable local detection")
        assertFalse(ProbeRuntime.session.snapshot().enabled)
    }
}
