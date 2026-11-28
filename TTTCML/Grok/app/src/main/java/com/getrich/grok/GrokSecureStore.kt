package com.getrich.grok

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object GrokSecureStore {
 private const val PREFS = "grok_secure_preferences"
 private const val API_KEY = "nvidia_api_key"
 private fun prefs(context: Context) = EncryptedSharedPreferences.create(context, PREFS, MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(), EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
 fun readApiKey(context: Context): String = prefs(context).getString(API_KEY, "").orEmpty()
 fun saveApiKey(context: Context, key: String) { prefs(context).edit().putString(API_KEY, key.trim()).apply() }
}
