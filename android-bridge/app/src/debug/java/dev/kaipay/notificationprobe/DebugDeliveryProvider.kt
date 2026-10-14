package dev.kaipay.notificationprobe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONObject
import org.json.JSONArray

/** Debug-only explicit owner-authorized shell harness. Never returns token or notification fields. */
class DebugDeliveryProvider : ContentProvider() {
    override fun onCreate() = true
    private fun authorize() {
        if (Binder.getCallingUid() != Process.SHELL_UID) throw SecurityException("Local ADB shell required")
        requireNotNull(context).enforceCallingPermission("android.permission.DUMP", "DUMP permission required")
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        authorize()
        val app = requireNotNull(context)
        val delivery = LiveDelivery.get(app)
        if (!app.notificationAccessGranted()) delivery.disable()
        return try {
            // Android's content CLI supplies an empty Bundle even when no --extra is provided.
            require((extras == null || extras.isEmpty) && (arg?.length ?: 0) <= 2048)
            when (method) {
                "status" -> Bundle().apply { putString("status", delivery.status()) }
                "events" -> Bundle().apply {
                    val rows = if (delivery.awaitReady()) delivery.store.displayRows() else emptyList()
                    putString("events", JSONArray(rows.map { JSONObject().put("eventId", it.candidate.eventId)
                        .put("source", it.candidate.source).put("status", it.status.name).put("attempts", it.attempts)
                        .put("invalidationAttempts", it.invalidationAttempts).put("uploaded", it.uploaded)
                        .put("invalidate", it.invalidate) }).toString())
                }
                "provision" -> {
                    val values = JSONObject(requireNotNull(arg))
                    require(values.keys().asSequence().toSet() == setOf("baseUrl", "deviceId", "token"))
                    delivery.configure(DeliveryConfig(values.getString("baseUrl"), values.getString("deviceId"),
                        values.getString("token")).validate(true))
                    Bundle().apply { putBoolean("queued", true) }
                }
                "enable" -> Bundle().apply { putBoolean("enabled", delivery.enable()) }
                "synthetic" -> Bundle().apply {
                    val amount = arg?.toLongOrNull()
                    putBoolean("queued", amount != null && amount in 1..1_000_000 && delivery.synthetic(amount))
                }
                "disable" -> { delivery.disable(); Bundle().apply { putBoolean("disabled", true) } }
                "clear" -> { delivery.clear(); Bundle().apply { putBoolean("queued", true) } }
                "forget" -> { delivery.forget(); Bundle().apply { putBoolean("queued", true) } }
                "retry" -> { delivery.retry(); Bundle().apply { putBoolean("queued", true) } }
                else -> throw UnsupportedOperationException("Unsupported delivery operation")
            }
        } catch (_: Exception) { Bundle().apply { putString("error", "Invalid delivery configuration or argument") } }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = unsupported()
    override fun getType(uri: Uri): String = unsupported()
    override fun insert(uri: Uri, values: ContentValues?): Uri = unsupported()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    private fun unsupported(): Nothing { authorize(); throw UnsupportedOperationException("Use delivery calls only") }
}
