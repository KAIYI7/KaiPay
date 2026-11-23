package dev.kaipay.notificationprobe

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings

internal fun Context.notificationAccessGranted(): Boolean {
    val listener = ComponentName(this, TngNotificationListener::class.java)
    return if (Build.VERSION.SDK_INT >= 27) {
        getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
    } else {
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == listener } == true
    }
}
