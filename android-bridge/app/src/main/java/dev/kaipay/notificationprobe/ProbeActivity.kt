package dev.kaipay.notificationprobe

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ProbeActivity : Activity() {
    private lateinit var status: TextView
    private var settingsUnavailable = false
    private val listener get() = ComponentName(this, TngNotificationListener::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        configureContentInsets()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        column.addView(TextView(this).apply {
            setText(R.string.probe_explanation)
        })
        fun button(label: Int, action: () -> Unit) {
            column.addView(Button(this).apply { setText(label); setOnClickListener { action(); refresh() } })
        }
        button(R.string.open_notification_settings) {
            settingsUnavailable = false
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (_: ActivityNotFoundException) { settingsUnavailable = true }
        }
        button(R.string.enable_detection) {
            if (notificationAccessGranted()) {
                ProbeRuntime.session.setEnabled(true)
                if (!ProbeRuntime.session.snapshot().connected) NotificationListenerService.requestRebind(listener)
            }
        }
        button(R.string.disable_and_clear) { ProbeRuntime.session.setEnabled(false) }
        button(R.string.clear_diagnostics) { ProbeRuntime.session.clear() }
        button(R.string.refresh_status) { }
        status = TextView(this)
        column.addView(status)
        val scroll = ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { view, insets ->
                applyContentInsets(view, insets)
                // Preserve dispatch to descendants; no other view applies these insets again.
                insets
            }
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        refresh()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refresh() }

    @Suppress("DEPRECATION")
    private fun configureContentInsets() {
        // Android 15 enforces edge-to-edge for target 35. Keep one owner for content padding.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun applyContentInsets(view: View, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        } else if (Build.VERSION.SDK_INT >= 28) {
            val cutout = insets.displayCutout
            view.setPadding(
                maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
            )
        } else {
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
    }

    private fun refresh() {
        val granted = notificationAccessGranted()
        if (!granted) ProbeRuntime.session.setEnabled(false)
        val snapshot = ProbeRuntime.session.snapshot()
        val events = snapshot.events.joinToString("\n") {
            getString(R.string.redacted_event, it.delivery, it.diagnostic.disposition,
                it.diagnostic.titlePresent, it.diagnostic.textPresent, it.diagnostic.bigTextPresent)
        }
        status.text = getString(R.string.probe_status,
            if (settingsUnavailable) getString(R.string.notification_settings_unavailable) else "",
            granted, snapshot.enabled, snapshot.connected, snapshot.events.size, events)
    }
}
