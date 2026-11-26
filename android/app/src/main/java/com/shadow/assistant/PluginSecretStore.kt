package com.shadow.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-plugin bearer secrets encrypted with a non-exportable Android Keystore key. */
internal object PluginSecretStore {
    private const val PREFS = "shadow_plugin_secrets"
    private const val ALIAS = "shadow_plugin_secret_aes"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun write(context: Context, pluginId: String, secret: String) {
        if (secret.isBlank()) { clear(context, pluginId); return }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(pluginId, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(context: Context, pluginId: String): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(pluginId, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 12)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear(context: Context, pluginId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(pluginId).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
