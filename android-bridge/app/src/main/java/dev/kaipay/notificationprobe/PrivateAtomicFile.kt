package dev.kaipay.notificationprobe

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** API26+ synced atomic replacement, with no non-atomic fallback. Callers serialize access. */
internal class PrivateAtomicFile(val baseFile: File) {
    private val temporary = File(baseFile.parentFile, baseFile.name + ".new")
    fun read(limit: Int): ByteArray {
        require(baseFile.length() <= limit)
        return baseFile.inputStream().use { input ->
            val bytes = input.readBytes(); require(bytes.size <= limit); bytes
        }
    }
    fun write(bytes: ByteArray) {
        val parent = requireNotNull(baseFile.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Private storage unavailable")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), baseFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) { temporary.delete(); throw failure }
    }
    fun delete() { if (baseFile.exists() && !baseFile.delete()) throw IOException("Private storage cleanup failed"); temporary.delete() }
}
