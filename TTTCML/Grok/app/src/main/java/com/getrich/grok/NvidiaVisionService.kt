package com.getrich.grok

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class VisionAction(val action: String, val x: Int, val y: Int, val text: String, val isOrderSubmission: Boolean, val reason: String)

class NvidiaVisionService {
    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    suspend fun analyze(apiKey: String, bitmap: Bitmap, strategy: String): VisionAction = withContext(Dispatchers.IO) {
        require(apiKey.startsWith("nvapi-")) { "Enter a valid NVIDIA API key beginning with nvapi-." }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
        val imageData = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        val prompt = """
You are a cautious visual chart assistant operating a DEMO trading browser.
Inspect the screenshot and follow this user strategy:
$strategy

Return exactly one JSON object and no markdown:
{"action":"wait","x":0,"y":0,"text":"","is_order_submission":false,"reason":"brief explanation"}
The action value must be exactly one of: click, type, wait. Output valid JSON.

Coordinates are measured on the supplied 1280x800 screenshot, origin at top-left.
Use wait if the chart is unclear, loading, login/2FA/CAPTCHA is visible, or no safe action is needed.
Never guess credentials, one-time codes, amounts, asset names, expiry, or prices.
Do not place or submit trades. If a click or typing step would place, confirm, or submit an order, set is_order_submission=true so the app pauses for human approval.
Only use click for ordinary navigation or chart controls. Only use type for ordinary non-sensitive text fields.
Never type into password, OTP, 2FA, payment, or security-code fields.
""".trimIndent()
        val payload = JSONObject().apply {
            put("model", "nvidia/nemotron-3.5-super-vl-120b-a12b")
            put("temperature", 0.1)
            put("max_tokens", 500)
            put("messages", org.json.JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", org.json.JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$imageData").put("detail", "high")))
                )
            }))
        }
        val request = Request.Builder().url("https://integrate.api.nvidia.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey").header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("NVIDIA API HTTP " + response.code + ": " + body.take(500))
            val content = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            parseAction(content)
        }
    }

    private fun parseAction(raw: String): VisionAction {
        val fence = "\u0060\u0060\u0060"
        val cleaned = raw.trim().removePrefix(fence + "json").removePrefix(fence).removeSuffix(fence).trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "Model did not return a JSON action." }
        val obj = JSONObject(JSONTokener(cleaned.substring(start, end + 1)))
        val action = obj.optString("action", "wait").lowercase()
        require(action in setOf("click", "type", "wait")) { "Unsupported action from model." }
        return VisionAction(action, obj.optInt("x", 0).coerceIn(0, 1279), obj.optInt("y", 0).coerceIn(0, 799),
            obj.optString("text", "").take(500), obj.optBoolean("is_order_submission", false), obj.optString("reason", "No explanation supplied.").take(500))
    }
}
