package com.mobileclaw.auth.chatgpt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class StoredCredentials(val tokens: StoredTokens, val account: ChatGptAccountInfo)
internal data class StoredTokens(val idToken: String?, val accessToken: String, val refreshToken: String?, val accessTokenExpiresAt: Long)

internal interface ChatGptCredentialRepository {
    fun save(tokens: ChatGptOAuthTokens, account: ChatGptAccountInfo)
    fun load(): Pair<ChatGptOAuthTokens, ChatGptAccountInfo>?
    /** Makes any previously persisted credential bundle unusable after restart. */
    fun destroy()
}

internal object ChatGptCredentialDestruction {
    fun requireSecure(preferencesCleared: Boolean, keyDestroyed: Boolean) {
        if (!preferencesCleared && !keyDestroyed) {
            throw ChatGptAuthException("Could not securely remove ChatGPT credentials.")
        }
    }
}

internal class ChatGptCredentialStore(context: Context) : ChatGptCredentialRepository {
    private val prefs = context.getSharedPreferences("chatgpt_oauth_encrypted_v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val alias = "com.mobileclaw.chatgpt.oauth.aes.v1"

    override fun save(tokens: ChatGptOAuthTokens, account: ChatGptAccountInfo) {
        val plaintext = gson.toJson(StoredCredentials(StoredTokens(tokens.idToken, tokens.accessToken, tokens.refreshToken, tokens.accessTokenExpiresAt), account)).toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(plaintext)
            val persisted = prefs.edit().putInt("version", 1)
                .putString("iv", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
                .putString("ciphertext", android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)).commit()
            if (!persisted) throw ChatGptAuthException("Could not save credentials securely.")
        } finally {
            plaintext.fill(0)
        }
    }

    override fun load(): Pair<ChatGptOAuthTokens, ChatGptAccountInfo>? {
        if (!prefs.contains("ciphertext")) return null
        return try {
            require(prefs.getInt("version", 0) == 1)
            val iv = android.util.Base64.decode(prefs.getString("iv", null), android.util.Base64.NO_WRAP)
            val ciphertext = android.util.Base64.decode(prefs.getString("ciphertext", null), android.util.Base64.NO_WRAP)
            val clear = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); doFinal(ciphertext)
            }
            val stored = gson.fromJson(String(clear), StoredCredentials::class.java)
            clear.fill(0)
            ChatGptOAuthTokens(stored.tokens.idToken, stored.tokens.accessToken, stored.tokens.refreshToken, stored.tokens.accessTokenExpiresAt) to stored.account
        } catch (_: Throwable) {
            runCatching { destroy() }
            null
        }
    }

    override fun destroy() {
        val preferencesCleared = prefs.edit().clear().commit()
        val keyDestroyed = runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            !keyStore.containsAlias(alias)
        }.getOrDefault(false)
        ChatGptCredentialDestruction.requireSecure(preferencesCleared, keyDestroyed)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
}
