package dev.kaipay.notificationprobe

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
// Keep the same supported Oracle JDK crypto environment as the listener/UI adapter tests.
@ConscryptMode(ConscryptMode.Mode.OFF)
class NotificationAccessAndManifestTest {
    @Test fun `modern OS grant and revoke require this exact listener component`() {
        val app = RuntimeEnvironment.getApplication()
        assertFalse(app.notificationAccessGranted())
        grantAccess(app)
        assertTrue(app.notificationAccessGranted())
        grantAccess(app, false)
        assertFalse(app.notificationAccessGranted())
    }

    @Test @Config(sdk = [26]) fun `API26 malformed and lookalike settings do not grant access`() {
        val app = RuntimeEnvironment.getApplication()
        val listener = ComponentName(app, TngNotificationListener::class.java).flattenToString()
        listOf(null, "", "malformed", listener + "Fake", app.packageName + "/.OtherSyntheticListener").forEach {
            Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", it)
            assertFalse(app.notificationAccessGranted())
        }
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "malformed:$listener:unrelated/.Service")
        assertTrue(app.notificationAccessGranted())
        grantAccess(app, false)
        assertFalse(app.notificationAccessGranted())
    }

    @Suppress("DEPRECATION")
    @Test fun `merged app manifest allows only network and WorkManager permissions`() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.FOREGROUND_SERVICE",
            "dev.kaipay.notificationprobe.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
            info.requestedPermissions?.toSet())
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }

    @Suppress("DEPRECATION")
    @Test fun `listener is guarded non exported and sole registered listener`() {
        val app = RuntimeEnvironment.getApplication()
        val component = ComponentName(app, TngNotificationListener::class.java)
        val service = app.packageManager.getServiceInfo(component, 0)
        assertFalse(service.exported)
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", service.permission)
        val intent = Intent(NotificationListenerService.SERVICE_INTERFACE).setPackage(app.packageName)
        val listeners = app.packageManager.queryIntentServices(intent, 0)
        assertEquals(1, listeners.size)
        assertEquals(TngNotificationListener::class.java.name, listeners.single().serviceInfo.name)
        val launcher = app.packageManager.getActivityInfo(ComponentName(app, ProbeActivity::class.java), 0)
        assertTrue(launcher.exported)
    }
}
