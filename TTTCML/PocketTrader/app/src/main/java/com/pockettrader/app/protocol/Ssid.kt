package com.pockettrader.app.protocol

import org.json.JSONObject
import org.json.JSONTokener

/**
 * A Pocket Option socket session as captured from the web platform's own
 * auth frame: 42["auth",{"session":"...","isDemo":1,"uid":123,"platform":2,...}]
 *
 * Demo sessions use a short token; real-account sessions use a PHP-serialized
 * string (session_id, ip_address, user_agent, last_activity + hash) and are bound
 * to the IP and User-Agent that created them.
 */
data class SessionInfo(
    val session: String,
    val isDemo: Boolean,
    val uid: Long,
    val platform: Int,
    /** The exact JSON object from the captured frame (sent back verbatim). */
    val authJson: String,
    /** WebSocket URL the browser used when the session was captured, if known. */
    val wsUrl: String? = null,
    /** User-Agent the session was created with (WebView UA or parsed from PHP session). */
    val userAgent: String? = null,
) {
    fun authFrame(): String = "42[\"auth\",$authJson]"

    /** Redacted description safe to show on screen / in logs. */
    fun describe(): String = "${if (isDemo) "Demo" else "Real"} session, uid $uid, platform $platform"
}

class SsidParseException(msg: String) : Exception(msg)

object SsidParser {
    private const val PREFIX = "42[\"auth\","

    fun parse(input: String, wsUrl: String? = null, userAgent: String? = null): SessionInfo {
        var t = input.trim()
        // Unwrap a JSON-encoded string ("42[\"auth\",...]") if pasted that way.
        if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) {
            val v = try { JSONTokener(t).nextValue() } catch (e: Exception) { null }
            t = if (v is String) v.trim() else t.substring(1, t.length - 1)
        }
        val json = when {
            t.startsWith(PREFIX) -> {
                if (!t.endsWith("]")) throw SsidParseException("Missing closing ] in auth frame")
                t.substring(PREFIX.length, t.length - 1)
            }
            t.startsWith("{") -> t
            else -> throw SsidParseException("Paste the whole message that starts with 42[\"auth\",{\"session\":...")
        }
        val o = try { JSONObject(json) } catch (e: Exception) { throw SsidParseException("Auth JSON is not valid: ${e.message}") }
        val session = o.optString("session").ifEmpty { o.optString("sessionToken") }
        if (session.isEmpty()) throw SsidParseException("No \"session\" field found")
        if (!o.has("session")) {
            throw SsidParseException("This is the sessionToken message. Use the one that has \"session\", \"isDemo\" and \"platform\".")
        }
        val uid = when (val u = o.opt("uid")) {
            is Number -> u.toLong()
            is String -> u.toLongOrNull() ?: throw SsidParseException("uid is not a number")
            else -> throw SsidParseException("No uid field found")
        }
        val currentUrl = o.optString("currentUrl")
        val isDemo = o.optInt("isDemo", 0) == 1 || currentUrl.contains("demo")
        val ua = userAgent ?: phpUserAgent(session)
        return SessionInfo(session, isDemo, uid, o.optInt("platform", 2), json, wsUrl, ua)
    }

    private val UA_REGEX = Regex("s:10:\\\"user_agent\\\";s:\\d+:\\\"([^\\\"]*)\\\"")
    private val IP_REGEX = Regex("s:10:\\\"ip_address\\\";s:\\d+:\\\"([^\\\"]*)\\\"")

    /** Pull the user agent out of a PHP-serialized real-account session string. */
    fun phpUserAgent(session: String): String? = UA_REGEX.find(session)?.groupValues?.get(1)
    fun phpIpAddress(session: String): String? = IP_REGEX.find(session)?.groupValues?.get(1)

    /** Quick check used by the WebView hook to keep only the right auth frame. */
    fun looksLikeUsableAuth(frame: String): Boolean =
        frame.startsWith(PREFIX) && frame.contains("\"session\"") && frame.contains("\"isDemo\"")
}

object Servers {
    /** From BinaryOptionsTools-v2 data/pocket_options_regions.json */
    val DEMO = listOf("wss://demo-api-eu.po.market/socket.io/?EIO=4&transport=websocket")
    val REAL = listOf(
        "wss://api-eu.po.market/socket.io/?EIO=4&transport=websocket",
        "wss://api-us-south.po.market/socket.io/?EIO=4&transport=websocket",
        "wss://api-asia.po.market/socket.io/?EIO=4&transport=websocket",
        "wss://api-msk.po.market/socket.io/?EIO=4&transport=websocket",
        "wss://api-spb.po.market/socket.io/?EIO=4&transport=websocket",
    )
    const val ORIGIN = "https://pocketoption.com"
    const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    fun candidates(s: SessionInfo): List<String> {
        val base = if (s.isDemo) DEMO else REAL
        val captured = s.wsUrl?.takeIf { it.startsWith("wss://") && it.contains("socket.io") }
        return (listOfNotNull(captured) + base).distinct()
    }
}
