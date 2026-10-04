package au.com.kit.fitnesslogsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Configuration(context: Context) {
    private val prefs = context.getSharedPreferences("configuration", Context.MODE_PRIVATE)
    val endpoint: String get() = prefs.getString("endpoint", "").orEmpty()
    val secret: String get() {
        val packed = prefs.getString("secret", "") ?: ""
        if (packed.isEmpty()) return ""
        val parts = packed.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun ready(): Boolean = endpoint.isNotEmpty() && runCatching { secret.length >= 32 }.getOrDefault(false)
    fun save(endpoint: String, secret: String) {
        val uri = URI(endpoint.trim())
        require(uri.scheme == "https" && uri.host == "script.google.com" && uri.path.startsWith("/macros/s/") && uri.path.endsWith("/exec") && uri.rawQuery == null && uri.userInfo == null) {
            "Paste the deployed Google Apps Script URL ending in /exec."
        }
        require(secret.trim().length >= 32) { "The connection key must contain at least 32 characters." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(secret.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("endpoint", endpoint.trim()).putString("secret", packed).commit()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("fitness-connection-key", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("fitness-connection-key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
