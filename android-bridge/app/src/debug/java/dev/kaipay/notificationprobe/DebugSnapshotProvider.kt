package dev.kaipay.notificationprobe

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/** Read-only, debug-only snapshot for an already authorized local ADB shell. No storage. */
class DebugSnapshotProvider : ContentProvider() {
    override fun onCreate() = true

    private fun authorize() {
        if (Binder.getCallingUid() != Process.SHELL_UID) throw SecurityException("Local ADB shell required")
        requireNotNull(context).enforceCallingPermission("android.permission.DUMP", "DUMP permission required")
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        authorize()
        require(uri == SNAPSHOT_URI && projection == null && selection == null &&
            selectionArgs == null && sortOrder == null) { "Only the snapshot route is supported" }
        val granted = requireNotNull(context).notificationAccessGranted()
        if (!granted) ProbeRuntime.disable()
        val snapshot = ProbeRuntime.session.snapshot()
        val observations = JSONArray()
        var exportBytes = 0
        if (granted && snapshot.enabled && snapshot.connected) snapshot.events.takeLast(4).forEach { event ->
            val row = JSONObject().put("delivery", event.delivery.name)
                .put("disposition", event.diagnostic.disposition.name)
            event.metadata?.let {
                row.put("sourceAlias", it.sourceAlias).put("postTime", it.postTime)
                    .put("notificationTime", it.notificationTime ?: JSONObject.NULL)
                    .put("channelAlias", it.channelAlias ?: JSONObject.NULL)
                    .put("grouped", it.grouped).put("groupSummary", it.groupSummary)
            }
            event.inspection?.let {
                row.put("fields", JSONObject(it.fields)).put("textLines", JSONArray(it.textLines))
                row.put("classification", JSONObject().put("outcome", it.classification.outcome.name)
                    .put("direction", it.classification.direction.name)
                    .put("amountMinor", it.classification.amountMinor ?: JSONObject.NULL)
                    .put("currency", it.classification.currency ?: JSONObject.NULL)
                    .put("explanation", it.classification.explanation)
                    .put("confidence", it.classification.confidence.name)
                    .put("ruleId", it.classification.ruleId ?: JSONObject.NULL))
            }
            val bytes = row.toString().toByteArray(Charsets.UTF_8).size
            if (exportBytes + bytes <= 120_000) { observations.put(row); exportBytes += bytes }
        }
        val payload = JSONObject().put("access", granted).put("enabled", snapshot.enabled)
            .put("connected", snapshot.connected).put("removals", snapshot.removals)
            .put("ignoredLateCallbacks", snapshot.ignoredLateCallbacks)
            .put("omittedEvents", snapshot.events.size - observations.length()).put("events", observations)
        return MatrixCursor(arrayOf("snapshot")).apply { addRow(arrayOf(payload.toString())) }
    }

    override fun getType(uri: Uri) = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri = unsupported()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = unsupported()
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = unsupported()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = unsupported()
    private fun unsupported(): Nothing { authorize(); throw UnsupportedOperationException("Read-only snapshot") }

    companion object {
        val SNAPSHOT_URI: Uri = Uri.parse("content://dev.kaipay.notificationprobe.diagnostics/snapshot")
    }
}
