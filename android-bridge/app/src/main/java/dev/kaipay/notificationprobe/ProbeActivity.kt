package dev.kaipay.notificationprobe

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import java.math.BigDecimal
import dev.kaipay.probe.RedactedEvent

class ProbeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var deliveryStatus: TextView
    private lateinit var credentialInput: EditText
    private var deliveryMessage = ""
    private var settingsUnavailable = false
    private val handler = Handler(Looper.getMainLooper())
    private val foregroundRefresh = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1_000) }
    }
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
        button(R.string.disable_and_clear) { ProbeRuntime.disable() }
        button(R.string.clear_diagnostics) { ProbeRuntime.session.clear() }
        button(R.string.refresh_status) { }
        column.addView(TextView(this).apply { setText(R.string.delivery_explanation) })
        fun input(hintId: Int, kind: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply {
            setHint(hintId); inputType = kind; isSaveEnabled = false; column.addView(this)
        }
        val baseInput = input(R.string.delivery_base_url, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val deviceInput = input(R.string.delivery_device_id)
        credentialInput = input(R.string.delivery_device_token, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        button(R.string.save_delivery_configuration) {
            try {
                LiveDelivery.get(this).configure(DeliveryConfig(baseInput.text.toString().trim(),
                    deviceInput.text.toString().trim(), credentialInput.text.toString()).validate(BuildConfig.DEBUG))
                credentialInput.text.clear(); deliveryMessage = getString(R.string.delivery_configuration_queued)
            } catch (_: Exception) { credentialInput.text.clear(); deliveryMessage = getString(R.string.delivery_configuration_invalid) }
        }
        button(R.string.enable_live_delivery) {
            deliveryMessage = getString(if (LiveDelivery.get(this).enable()) R.string.delivery_enabled else R.string.delivery_enable_requires)
        }
        button(R.string.disable_live_delivery) { LiveDelivery.get(this).disable(); deliveryMessage = "" }
        button(R.string.retry_delivery) { LiveDelivery.get(this).retry() }
        button(R.string.clear_delivery_history) { LiveDelivery.get(this).clear() }
        button(R.string.forget_delivery_credential) { LiveDelivery.get(this).forget() }
        if (BuildConfig.DEBUG) {
            val amountInput = input(R.string.synthetic_amount_cents, InputType.TYPE_CLASS_NUMBER)
            button(R.string.synthetic_delivery_trigger) {
                val amount = amountInput.text.toString().toLongOrNull()
                deliveryMessage = getString(if (amount != null && amount in 1..1_000_000 && LiveDelivery.get(this).synthetic(amount))
                    R.string.synthetic_queued else R.string.synthetic_requires)
            }
        }
        deliveryStatus = TextView(this); column.addView(deliveryStatus)
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

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) { refresh(); handler.removeCallbacks(foregroundRefresh); handler.postDelayed(foregroundRefresh, 1_000) }
    }
    override fun onPause() {
        handler.removeCallbacks(foregroundRefresh)
        if (::status.isInitialized) status.setText(R.string.content_hidden_while_paused)
        if (::deliveryStatus.isInitialized) deliveryStatus.setText(R.string.content_hidden_while_paused)
        if (::credentialInput.isInitialized) credentialInput.text.clear()
        super.onPause()
    }
    override fun onDestroy() { handler.removeCallbacks(foregroundRefresh); super.onDestroy() }

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
        if (!granted) ProbeRuntime.disable()
        val snapshot = ProbeRuntime.session.snapshot()
        deliveryStatus.text = getString(R.string.delivery_status, deliveryMessage, LiveDelivery.get(this).status())
        val events = snapshot.events.joinToString("\n\n") { describe(it) }
        status.text = getString(R.string.probe_status,
            if (settingsUnavailable) getString(R.string.notification_settings_unavailable) else "",
            granted, snapshot.enabled, snapshot.connected, snapshot.events.size,
            snapshot.removals, snapshot.ignoredLateCallbacks, events)
    }

    private fun describe(event: RedactedEvent): String = buildString {
        append(getString(R.string.redacted_event, event.delivery, event.diagnostic.disposition,
            event.diagnostic.titlePresent, event.diagnostic.textPresent, event.diagnostic.bigTextPresent))
        event.metadata?.let { meta ->
            append(getString(R.string.event_metadata, meta.sourceAlias, meta.postTime,
                meta.notificationTime?.toString() ?: getString(R.string.not_available),
                meta.channelAlias ?: getString(R.string.not_available), meta.grouped, meta.groupSummary))
        }
        event.inspection?.let { inspection ->
            val result = inspection.classification
            val amount = result.amountMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() }
                ?: getString(R.string.not_available)
            append(getString(R.string.classification_result, result.outcome, result.direction, amount,
                result.currency ?: getString(R.string.not_available), result.confidence,
                result.reference ?: getString(R.string.not_available), result.ruleId ?: getString(R.string.not_available),
                result.explanation))
            inspection.fields.forEach { (name, value) -> append(getString(R.string.inspected_field, name, value)) }
            inspection.textLines.forEachIndexed { index, value -> append(getString(R.string.inspected_line, index, value)) }
        }
    }
}
