package com.getrich.deriv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class DerivApiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun fetchOptionsAccounts(appId: String, token: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(appId.isNotBlank()) { "Deriv App ID is required." }
                require(token.isNotBlank()) { "Deriv Personal Access Token is required." }
                val request = Request.Builder()
                    .url("https://api.derivws.com/trading/v1/options/accounts")
                    .header("Authorization", "Bearer $token")
                    .header("Deriv-App-ID", appId)
                    .header("Accept", "application/json")
                    .get().build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val safeBody = body.take(500).replace(token, "[REDACTED]")
                        error("Deriv API returned HTTP ${response.code}: $safeBody")
                    }
                    body
                }
            }
        }
}
