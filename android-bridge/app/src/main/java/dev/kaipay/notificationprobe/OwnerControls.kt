package dev.kaipay.notificationprobe

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Small durable opt-in barrier, separate from asynchronous event persistence. No notification data. */
internal class OwnerControls(directory: File) {
    private val file = PrivateAtomicFile(File(directory, "owner-controls.json"))
    private val failureMarker = File(directory, "owner-controls.failed")
    val exists get() = file.baseFile.exists()
    @Volatile var capture = false; private set
    @Volatile var delivery = false; private set
    @Volatile var healthy = true; private set
    init {
        if (file.baseFile.exists()) try {
            val value = JSONObject(String(file.read(256), Charsets.UTF_8))
            capture = value.getBoolean("capture"); delivery = value.getBoolean("delivery")
        } catch (_: Exception) { healthy = false }
        if (failureMarker.exists() || !healthy) { capture = false; delivery = false; healthy = false }
    }
    @Synchronized fun set(capture: Boolean? = null, delivery: Boolean? = null): Boolean {
        val nextCapture = capture ?: this.capture
        val nextDelivery = delivery ?: this.delivery
        if (healthy && exists && nextCapture == this.capture && nextDelivery == this.delivery) return true
        // Publish opt-in only after durable replacement. Failure always closes the runtime gate.
        try {
            file.write(JSONObject().put("capture", nextCapture).put("delivery", nextDelivery).toString().toByteArray(Charsets.UTF_8))
            if (failureMarker.exists() && !failureMarker.delete()) throw java.io.IOException("Control storage unavailable")
            this.capture = nextCapture; this.delivery = nextDelivery; healthy = true
            return true
        } catch (_: Exception) {
            this.capture = false; this.delivery = false; healthy = false
            try { failureMarker.writeText("DISABLED") } catch (_: Exception) { }
            return false
        }
    }
}

internal object OwnerControlRuntime {
    private var directory: File? = null
    private var controls: OwnerControls? = null
    @Synchronized fun get(context: Context): OwnerControls {
        val target = context.applicationContext.noBackupFilesDir
        if (directory != target || controls == null) { directory = target; controls = OwnerControls(target) }
        return requireNotNull(controls)
    }
}
