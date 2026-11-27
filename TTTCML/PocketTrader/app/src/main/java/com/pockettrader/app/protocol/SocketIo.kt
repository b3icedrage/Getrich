package com.pockettrader.app.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Minimal Engine.IO v4 / Socket.IO v4 text-frame codec for the Pocket Option
 * web-platform socket (wss://.../socket.io/?EIO=4&transport=websocket).
 *
 * Frame shapes seen on this socket (see README "Protocol sources"):
 *  0{"sid":..}            engine.io open        -> client replies "40"
 *  40{"sid":..}           socket.io connected   -> client sends 42["auth",{...}]
 *  2                      ping                  -> client replies "3"
 *  41                     disconnect (often = auth rejected)
 *  42["event",payload]    event with inline JSON payload
 *  451-["event",{"_placeholder":true,"num":0}]  event whose payload follows
 *                         in the next *binary* websocket frame (UTF-8 JSON)
 */
object SocketIo {

    sealed class Frame {
        data class Open(val sid: String?, val pingIntervalMs: Long) : Frame()
        object Ping : Frame()
        object Pong : Frame()
        data class Connected(val sid: String?) : Frame()
        object Disconnect : Frame()
        data class ConnectError(val raw: String) : Frame()
        /** Event with payload already present. payload is a JSONObject/JSONArray/primitive or null. */
        data class Event(val name: String, val payload: Any?) : Frame()
        /** Event header announcing that the payload comes in the next binary frame. */
        data class BinaryEventHeader(val name: String, val attachments: Int) : Frame()
        data class Unknown(val raw: String) : Frame()
    }

    fun parse(text: String): Frame {
        if (text.isEmpty()) return Frame.Unknown(text)
        return try {
            when {
                text == "2" -> Frame.Ping
                text == "3" -> Frame.Pong
                text.startsWith("0{") -> {
                    val o = JSONObject(text.substring(1))
                    Frame.Open(o.optString("sid").ifEmpty { null }, o.optLong("pingInterval", 25000))
                }
                text == "40" -> Frame.Connected(null)
                text.startsWith("40{") -> Frame.Connected(JSONObject(text.substring(2)).optString("sid").ifEmpty { null })
                text.startsWith("41") -> Frame.Disconnect
                text.startsWith("44") -> Frame.ConnectError(text)
                text.startsWith("42[") -> parseEventArray(text.substring(2), null)
                text.startsWith("45") -> {
                    val dash = text.indexOf('-')
                    if (dash < 0) return Frame.Unknown(text)
                    val n = text.substring(2, dash).toIntOrNull() ?: 1
                    parseEventArray(text.substring(dash + 1), n)
                }
                else -> Frame.Unknown(text)
            }
        } catch (e: Exception) {
            Frame.Unknown(text)
        }
    }

    private fun parseEventArray(json: String, attachments: Int?): Frame {
        val arr = JSONArray(json)
        val name = arr.optString(0)
        val payload: Any? = if (arr.length() > 1) arr.get(1) else null
        if (attachments != null && hasPlaceholder(payload)) {
            return Frame.BinaryEventHeader(name, attachments)
        }
        return Frame.Event(name, if (payload == JSONObject.NULL) null else payload)
    }

    fun hasPlaceholder(payload: Any?): Boolean =
        payload is JSONObject && payload.has("_placeholder")

    /** Decode the body of a binary attachment frame (UTF-8 JSON). */
    fun decodeBinaryPayload(bytes: ByteArray): Any? {
        var text = String(bytes, Charsets.UTF_8)
        // Some transports prefix binary frames with engine.io type byte 0x04.
        if (text.isNotEmpty() && text[0] == '\u0004') text = text.substring(1)
        return try { JSONTokener(text).nextValue() } catch (e: Exception) { null }
    }

    fun event(name: String, payload: Any? = null): String {
        val arr = JSONArray().put(name)
        if (payload != null) arr.put(payload)
        return "42$arr"
    }
}

/** Outgoing frames used by the client. */
object PocketFrames {
    const val CONNECT = "40"
    const val PONG = "3"
    val KEEP_ALIVE = SocketIo.event("ps")
    val GET_BALANCE = SocketIo.event("getBalance")
    val LOAD_ASSETS = SocketIo.event("assets/load")

    fun changeSymbol(asset: String, period: Int): String =
        SocketIo.event("changeSymbol", JSONObject().put("asset", asset).put("period", period))

    /**
     * 42["loadHistoryPeriod",{"asset":..,"period":60,"time":<end unix sec>,"index":<n>,"offset":<lookback>}]
     * Exact meaning of offset is not documented; BinaryOptionsTools-v2 describes it as the
     * number of periods to look back (python docs) and also uses seconds in tests. We send
     * periods*period seconds capped, and parse whatever comes back.
     */
    fun loadHistoryPeriod(asset: String, period: Int, endTimeSec: Long, offset: Long, index: Long): String =
        SocketIo.event("loadHistoryPeriod", JSONObject().put("asset", asset).put("period", period)
            .put("time", endTimeSec).put("index", index).put("offset", offset))

    fun subscribe(asset: String): String = SocketIo.event("subfor", asset)

    /**
     * 42["openOrder",{"asset":"EURUSD_otc","amount":1.0,"action":"call","isDemo":1,
     *                  "requestId":"<uuid>","optionType":100,"time":60}]
     * optionType 100 = timer-based expiry, time = duration in seconds.
     */
    fun openOrder(asset: String, amount: Double, direction: Direction, timeSec: Int, isDemo: Boolean, requestId: String): String {
        require(amount > 0) { "amount must be > 0" }
        require(timeSec > 0) { "time must be > 0" }
        val o = JSONObject()
            .put("asset", asset)
            .put("amount", amount)
            .put("action", direction.wire)
            .put("isDemo", if (isDemo) 1 else 0)
            .put("requestId", requestId)
            .put("optionType", 100)
            .put("time", timeSec)
        return SocketIo.event("openOrder", o)
    }
}

enum class Direction(val wire: String, val label: String) {
    CALL("call", "Higher"), PUT("put", "Lower");
}
