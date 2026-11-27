package com.pockettrader.app.trading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatTurn(val role: String, val content: String)

class NemotronClient {
    private val http = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun chat(apiKey: String, history: List<ChatTurn>, userMessage: String): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw IOException("NVIDIA API key is missing")
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            (history.takeLast(12) + ChatTurn("user", userMessage)).forEach { turn ->
                if (turn.role in setOf("user", "assistant")) {
                    messages.put(JSONObject().put("role", turn.role).put("content", turn.content.take(6000)))
                }
            }
            val payload = JSONObject()
                .put("model", MODEL).put("messages", messages)
                .put("temperature", 0.4).put("top_p", 0.95)
                .put("max_tokens", 900).put("stream", false)
            val request = Request.Builder().url(ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull().orEmpty()
                    throw IOException("NVIDIA API error ${response.code}: ${detail.ifBlank { raw.take(240) }}")
                }
                val content = JSONObject(raw).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
                if (content.isBlank()) throw IOException("Nemotron returned an empty reply")
                content
            }
        }

    companion object {
        const val MODEL = "nvidia/nemotron-3.5-lightning-30b-a3b"
        const val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val SYSTEM_PROMPT = """You are Nemotron, a helpful conversational assistant inside PocketTrader.
You may explain trading concepts, indicators, market structure, risk management, and how the app works.
You are a chat assistant only: you do not approve, reject, create, or execute trades, and you do not control the signal engine.
Do not claim access to live prices, news, the user's account, or online sources unless that information is explicitly provided in the chat.
Never promise profits, guaranteed win rates, or certainty. Clearly distinguish educational explanations from personalized financial advice.
Keep answers practical, cautious, and concise."""
    }
}
