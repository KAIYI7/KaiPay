package dev.kaipay.notificationprobe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The token is encrypted with a non-exportable Android Keystore key; backup is disabled. */
internal interface DeviceCredentialStorage {
    fun save(config: DeliveryConfig)
    fun load(): DeliveryConfig?
    fun clear()
}
internal class DeliveryCredentials(context: Context) : DeviceCredentialStorage {
    private val file = PrivateAtomicFile(File(context.noBackupFilesDir, "device-config.enc"))
    private val alias = "kaipay.experimental.device.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    @Synchronized override fun save(config: DeliveryConfig) {
        val plain = JSONObject().put("baseUrl", config.baseUrl).put("deviceId", config.deviceId).put("token", config.token)
            .toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(plain)
            file.write(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        } finally { plain.fill(0) }
    }
    @Synchronized override fun load(): DeliveryConfig? {
        if (!file.baseFile.exists()) return null
        val bytes = file.read(4096)
        require(bytes.isNotEmpty() && bytes[0].toInt() == 12 && bytes.size > 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        }
        val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return try {
            val root = JSONObject(String(plain, Charsets.UTF_8))
            DeliveryConfig(root.getString("baseUrl"), root.getString("deviceId"), root.getString("token"))
                .validate(BuildConfig.DEBUG)
        } finally { plain.fill(0) }
    }
    @Synchronized override fun clear() {
        try { file.delete() }
        finally { KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) } }
    }
}
