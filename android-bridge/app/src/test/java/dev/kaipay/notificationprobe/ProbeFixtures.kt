package dev.kaipay.notificationprobe

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import dev.kaipay.probe.ProbeParser
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// All contents and identifiers below are invented JVM fixtures, never real TNG captures.
internal const val SYNTHETIC_PRIVATE = "SYNTHETIC_PRIVATE_DO_NOT_RETAIN"

internal fun grantAccess(context: Context = RuntimeEnvironment.getApplication(), granted: Boolean = true) {
    val component = ComponentName(context, TngNotificationListener::class.java)
    if (Build.VERSION.SDK_INT >= 27) {
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(component, granted)
    } else {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners",
            if (granted) component.flattenToString() else "")
    }
}

internal fun syntheticNotification(
    packageName: String = ProbeParser.OFFICIAL_PACKAGE,
    id: Int = 1,
    time: Long = 100,
    extras: Bundle? = Bundle().apply { putString(Notification.EXTRA_TEXT, SYNTHETIC_PRIVATE) }
): StatusBarNotification {
    val notification = Notification().apply { this.extras = extras }
    @Suppress("DEPRECATION")
    return StatusBarNotification(packageName, packageName, id, "SYNTHETIC_TAG", 0, 0, 0,
        notification, UserHandle.getUserHandleForUid(0), time)
}

internal class CountingSequence(private val value: String, private val failOnRead: Boolean = false) : CharSequence {
    var reads = 0
        private set
    private fun read() { reads++; if (failOnRead) error("Synthetic forbidden read") }
    override val length: Int get() { read(); return value.length }
    override fun get(index: Int): Char { read(); return value[index] }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        read(); return value.subSequence(startIndex, endIndex)
    }
    override fun toString(): String { read(); return value }
}
