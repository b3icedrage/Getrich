package com.getrich.deriv

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureCredentials(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val prefs = EncryptedSharedPreferences.create(
        context, "deriv_secure_credentials", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun save(appId: String, token: String) {
        prefs.edit().putString("app_id", appId.trim()).putString("pat", token.trim()).apply()
    }
    fun appId(): String = prefs.getString("app_id", "").orEmpty()
    fun token(): String = prefs.getString("pat", "").orEmpty()
    fun clear() { prefs.edit().clear().apply() }
}
