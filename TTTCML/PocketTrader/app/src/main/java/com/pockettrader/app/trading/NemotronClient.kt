package com.pockettrader.app.trading

import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.protocol.Direction
import com.pockettrader.app.signals.Signal
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

data class AiTradeDecision(
    val shouldTrade: Boolean,
    val direction: Direction?,
    val confidence: Int,
    val rationale: String,
)

class NemotronClient {
    private val http = OkHttpClient.Builder()
        .callTimeout(35, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun analyze(apiKey: String, signal: Signal, candles: List<Candle>, payout: Int?): AiTradeDecision =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) throw IOException("NVIDIA API key is missing")
            val candleRows = JSONArray()
            candles.takeLast(30).forEach { c ->
                candleRows.put(JSONObject()
                    .put("open", c.open).put("high", c.high).put("low", c.low)
                    .put("close", c.close).put("tick_volume_proxy", c.volume))
            }
            val context = JSONObject()
                .put("asset", signal.asset)
                .put("expiry_seconds", signal.expirySec)
                .put("candidate_direction", if (signal.direction == Direction.CALL) "BUY" else "SELL")
                .put("technical_strategy", signal.strategy.label)
                .put("technical_reason", signal.reason)
                .put("payout_percent", payout ?: JSONObject.NULL)
                .put("recent_ohlc_candles_oldest_to_newest", candleRows)
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", context.toString()))
            val payload = JSONObject()
                .put("model", MODEL).put("messages", messages)
                .put("temperature", 0.2).put("top_p", 0.95)
                .put("max_tokens", 512).put("stream", false)
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
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                parseDecision(content)
            }
        }

    private fun parseDecision(content: String): AiTradeDecision {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("Nemotron returned no valid JSON decision")
        val json = runCatching { JSONObject(content.substring(start, end + 1)) }
            .getOrElse { throw IOException("Nemotron response could not be parsed") }
        val action = json.optString("action", "NO_TRADE").uppercase()
        val confidence = json.optInt("confidence", 0).coerceIn(0, 100)
        val rationale = json.optString("rationale", "No explanation supplied").take(500)
        val direction = when (action) {
            "BUY" -> Direction.CALL
            "SELL" -> Direction.PUT
            else -> null
        }
        val approved = direction != null && confidence >= MIN_CONFIDENCE
        val reason = if (approved) rationale else
            "NO TRADE: ${if (direction == null) rationale else "confidence below $MIN_CONFIDENCE% — $rationale"}"
        return AiTradeDecision(approved, if (approved) direction else null, confidence, reason)
    }

    companion object {
        const val MODEL = "nvidia/nemotron-3.5-lightning-30b-a3b"
        const val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
        const val MIN_CONFIDENCE = 65
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val SYSTEM_PROMPT = """You are a cautious market-analysis assistant for a demo-first binary-options simulator.
Analyze ONLY the supplied OHLC candles and technical candidate. Do not invent indicators, news, prices, or future facts.
The candidate direction is a technical proposal, not an instruction. Independently assess trend, momentum, candle structure, volatility and conflicting evidence.
If candles are insufficient, conditions are choppy, evidence conflicts, or the setup is weak, choose NO_TRADE.
Return exactly one JSON object and no markdown:
{"action":"BUY|SELL|NO_TRADE","confidence":0,"rationale":"brief evidence-based explanation"}
Confidence is a heuristic strength score, NOT a probability of profit or win rate. Never claim certainty. Prefer NO_TRADE over forcing a direction."""
    }
}
