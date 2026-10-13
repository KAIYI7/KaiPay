package dev.kaipay.notificationprobe

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.kaipay.probe.Fields
import dev.kaipay.probe.ProbeParser
import dev.kaipay.probe.ProbeSession

internal object ProbeRuntime { val session = ProbeSession() }

class TngNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { ProbeRuntime.session.setConnected(true) }
    override fun onListenerDisconnected() { ProbeRuntime.session.setConnected(false) }
    override fun onDestroy() {
        ProbeRuntime.session.setConnected(false)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Check package and user gate BEFORE touching extras or content.
        if (sbn?.packageName != ProbeParser.OFFICIAL_PACKAGE) return
        val session = ProbeRuntime.session
        if (!notificationAccessGranted()) { session.setEnabled(false); return }
        val status = session.snapshot()
        if (!status.enabled || !status.connected) return
        try {
            session.posted(sbn.packageName, sbn.key, sbn.postTime) {
                val extras = sbn.notification.extras
                fun field(name: String): Any? {
                    @Suppress("DEPRECATION") val value = extras?.get(name)
                    return if (value is CharSequence && value.length <= 4096) value.toString() else value
                }
                Fields(field(Notification.EXTRA_TITLE), field(Notification.EXTRA_TEXT), field(Notification.EXTRA_BIG_TEXT))
            }
        } catch (_: RuntimeException) {
            // Malformed parcel/extra: drop without logging exception or notification content.
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName != ProbeParser.OFFICIAL_PACKAGE) return
        // Retain no source IDs or content. Clear ephemeral diagnostics on removal, including lockdown.
        ProbeRuntime.session.clear()
    }
}
