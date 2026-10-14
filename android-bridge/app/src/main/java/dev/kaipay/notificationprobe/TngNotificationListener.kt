package dev.kaipay.notificationprobe

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.kaipay.probe.Fields
import dev.kaipay.probe.NotificationMetadata
import dev.kaipay.probe.ProbeParser
import dev.kaipay.probe.ProbeSession
import dev.kaipay.probe.TngNotificationProfiles

internal object ProbeRuntime {
    val session = ProbeSession(elapsedClock = { android.os.SystemClock.elapsedRealtime() },
        profile = TngNotificationProfiles.current)
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = object : Runnable {
        override fun run() { scheduleExpiry() }
    }
    fun scheduleExpiry() {
        handler.removeCallbacks(expiry)
        // snapshot prunes using a monotonic clock. Display/export also prune before reads.
        if (session.snapshot().events.isNotEmpty()) handler.postDelayed(expiry, 30_000)
    }
    fun disable() { session.setEnabled(false); handler.removeCallbacks(expiry); LiveDelivery.disableIfInitialized() }
}

class TngNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { ProbeRuntime.session.setConnected(true); LiveDelivery.get(this) }
    override fun onListenerDisconnected() {
        ProbeRuntime.session.setConnected(false); LiveDelivery.get(this).disable(); ProbeRuntime.scheduleExpiry()
    }
    override fun onDestroy() {
        ProbeRuntime.session.setConnected(false); LiveDelivery.get(this).disable(); ProbeRuntime.scheduleExpiry()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Source/access/user gates precede ALL extras, channel and grouping inspection.
        if (sbn?.packageName != ProbeParser.OFFICIAL_PACKAGE) return
        if (!notificationAccessGranted()) { ProbeRuntime.disable(); return }
        val session = ProbeRuntime.session
        val state = session.snapshot()
        if (!state.enabled || !state.connected) return
        try {
            val notification = sbn.notification
            val metadata = NotificationMetadata(notification.channelId, notification.`when`, sbn.isGroup,
                notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            val event = session.posted(sbn.packageName, sbn.key, sbn.postTime, metadata) {
                val extras = notification.extras
                fun field(name: String): Any? {
                    @Suppress("DEPRECATION") val value = extras?.get(name)
                    // Never truncate before the whole-field credential/size gate.
                    return if (value is CharSequence && value.length <= 4096) value.toString() else value
                }
                @Suppress("DEPRECATION") val rawLines = extras?.get(Notification.EXTRA_TEXT_LINES)
                val lines = if (rawLines is Array<*> && rawLines.size <= 16) rawLines.map { value ->
                    if (value is CharSequence && value.length <= 4096) value.toString() else value
                } else rawLines
                Fields(field(Notification.EXTRA_TITLE), field(Notification.EXTRA_TEXT), field(Notification.EXTRA_BIG_TEXT),
                    field(Notification.EXTRA_SUB_TEXT), field(Notification.EXTRA_SUMMARY_TEXT), lines)
            }
            if (event != null) LiveDelivery.get(this).observed(sbn.key, event)
            ProbeRuntime.scheduleExpiry()
        } catch (_: RuntimeException) {
            // Invalid parcels/fields are dropped. Never log exception text or notification data.
            try { LiveDelivery.get(this).unreadable(sbn.key, sbn.postTime) } catch (_: RuntimeException) { }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName != ProbeParser.OFFICIAL_PACKAGE) return
        if (!notificationAccessGranted()) { ProbeRuntime.disable(); return }
        try {
            ProbeRuntime.session.removed(sbn.packageName, sbn.key, sbn.postTime)
            LiveDelivery.get(this).removed(sbn.key, sbn.postTime)
        }
        catch (_: RuntimeException) { ProbeRuntime.session.clear() }
        ProbeRuntime.scheduleExpiry()
    }
}
