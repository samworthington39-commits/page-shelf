package com.example.bookshelf.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureCredentialStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("secure_session", Context.MODE_PRIVATE)

    fun clearPassword() = preferences.edit { remove(KEY_PASSWORD) }

    fun saveSession(token: String) {
        runCatching { putEncrypted(KEY_TOKEN, token) }
            .recoverCatching {
                // An OS upgrade or restored app data can leave an unusable Keystore
                // entry behind. The server token is replaceable, so recreate only
                // this app-owned key and retry once.
                resetEncryptionKey()
                putEncrypted(KEY_TOKEN, token)
            }
            .getOrThrow()
    }

    // 会话不设有效时限：只要服务器仍接受该令牌就持续有效，
    // 服务器修改管理密码后才会返回 401 并触发重新登录。
    fun bearerToken(): String? = getEncrypted(KEY_TOKEN)

    fun clearSession() {
        preferences.edit {
            remove(KEY_TOKEN)
        }
    }

    fun clearAll() = preferences.edit { clear() }

    private fun putEncrypted(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit { putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)) }
    }

    private fun getEncrypted(key: String): String? = runCatching {
        val payload = Base64.decode(preferences.getString(key, null) ?: return null, Base64.NO_WRAP)
        if (payload.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, payload.copyOfRange(0, IV_BYTES)),
        )
        String(cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size)), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun resetEncryptionKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        clearSession()
    }

    private companion object {
        const val KEY_ALIAS = "page_shelf_credentials_v1"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
