package com.pockettrader.app.protocol

import org.json.JSONArray
import org.json.JSONObject

data class Asset(
    val id: Int,
    val symbol: String,
    val name: String,
    val type: String,
    val payout: Int,
    val isOtc: Boolean,
    val isActive: Boolean,
)

data class Tick(val symbol: String, val timeSec: Double, val price: Double)

/**
 * OHLC candle. [volume] is a *tick-count proxy*: Pocket Option's stream carries no traded
 * volume, so live candles count price updates; seeded history candles use the server's
 * volume field when present, otherwise 0 (unknown).
 */
data class Candle(
    val startSec: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 1.0,
) {
    fun update(price: Double) = copy(high = maxOf(high, price), low = minOf(low, price), close = price, volume = volume + 1)
}

data class HistorySnapshot(val asset: String, val period: Int, val ticks: List<Pair<Double, Double>>, val candles: List<Candle>)

data class Deal(
    val id: String,
    val requestId: String?,
    val asset: String,
    val amount: Double,
    val profit: Double?,
    val openPrice: Double?,
    val closePrice: Double?,
    val direction: Direction?,
    val openTimestamp: Long?,
    val closeTimestamp: Long?,
    val isDemo: Boolean,
)

data class FailedOrder(val error: String, val asset: String, val amount: Double)

/** Parsers for server event payloads. Field layouts from BinaryOptionsTools-v2 types.rs. */
object PocketParsers {

    /**
     * updateAssets: array of rows. Row indexes used:
     * 0 id, 1 symbol, 2 name, 3 type, 5 payout, 9 is_otc(1/0), 14 is_active(bool)
     */
    fun assets(payload: Any?): List<Asset> {
        val arr = payload as? JSONArray ?: return emptyList()
        val out = ArrayList<Asset>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            if (row.length() < 15) continue
            try {
                out += Asset(
                    id = row.optInt(0),
                    symbol = row.getString(1),
                    name = row.optString(2),
                    type = row.optString(3),
                    payout = row.optInt(5),
                    isOtc = row.optInt(9) == 1,
                    isActive = row.optBoolean(14, false),
                )
            } catch (_: Exception) { }
        }
        return out
    }

    /** updateStream: [["SYMBOL",ts,price], ...] (one row per subscribed symbol) */
    fun ticks(payload: Any?): List<Tick> {
        val arr = payload as? JSONArray ?: return emptyList()
        val out = ArrayList<Tick>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            if (row.length() < 3) continue
            val sym = row.optString(0)
            val ts = normalizeTs(row.optDouble(1))
            val price = row.optDouble(2)
            if (sym.isNotEmpty() && !ts.isNaN() && !price.isNaN()) out += Tick(sym, ts, price)
        }
        return out
    }

    /** updateHistoryNewFast / updateHistoryNew / history: {asset, period, history:[[ts,price]], candles:[[ts,o,c,h,l]]} */
    fun history(payload: Any?): HistorySnapshot? {
        val o = payload as? JSONObject ?: return null
        val asset = o.optString("asset").ifEmpty { return null }
        val period = o.optInt("period", 60)
        val ticks = ArrayList<Pair<Double, Double>>()
        o.optJSONArray("history")?.let { h ->
            for (i in 0 until h.length()) {
                val r = h.optJSONArray(i) ?: continue
                if (r.length() >= 2) ticks += normalizeTs(r.optDouble(0)) to r.optDouble(1)
            }
        }
        val candles = ArrayList<Candle>()
        o.optJSONArray("candles")?.let { c ->
            for (i in 0 until c.length()) {
                val r = c.optJSONArray(i) ?: continue
                if (r.length() >= 5) {
                    // BaseCandle order: [timestamp, open, close, high, low, (volume)]
                    val vol = if (r.length() >= 6) r.optDouble(5, 0.0) else 0.0
                    candles += Candle(normalizeTs(r.optDouble(0)).toLong(), r.optDouble(1), r.optDouble(3), r.optDouble(4), r.optDouble(2), vol)
                }
            }
        }
        candles.sortBy { it.startSec }
        ticks.sortBy { it.first }
        return HistorySnapshot(asset, period, ticks, candles)
    }

    /**
     * loadHistoryPeriod response: {"asset","index","period","data":[{time,open,close,high,low}|{time,price}]}.
     * Returns candles (when OHLC present) and ticks (when only price present).
     */
    fun loadHistory(payload: Any?): HistorySnapshot? {
        val o = payload as? JSONObject ?: return null
        val data = o.optJSONArray("data") ?: return null
        val period = o.optInt("period", 60)
        val candles = ArrayList<Candle>(); val ticks = ArrayList<Pair<Double, Double>>()
        for (i in 0 until data.length()) {
            val d = data.optJSONObject(i) ?: continue
            val t = normalizeTs(d.optDouble("time"))
            if (t.isNaN()) continue
            if (d.has("open") && d.has("close")) {
                val c = d.optDouble("close")
                candles += Candle(t.toLong(), d.optDouble("open"), d.optDouble("high", c), d.optDouble("low", c), c, d.optDouble("volume", 0.0))
            } else if (d.has("price")) ticks += t to d.optDouble("price")
        }
        candles.sortBy { it.startSec }; ticks.sortBy { it.first }
        return HistorySnapshot(o.optString("asset"), period, ticks, candles)
    }

    /** successupdateBalance: {"isDemo":1,"balance":123.45} */
    fun balance(payload: Any?): Pair<Double, Boolean?>? {
        val o = payload as? JSONObject ?: return null
        if (!o.has("balance")) return null
        val demo = if (o.has("isDemo")) o.optInt("isDemo") == 1 else null
        return o.optDouble("balance") to demo
    }

    fun deal(o: JSONObject): Deal? {
        val id = o.optString("id").ifEmpty { return null }
        val cmd = if (o.has("command")) o.optInt("command", -1) else -1
        return Deal(
            id = id,
            requestId = if (o.has("requestId") && !o.isNull("requestId")) o.get("requestId").toString() else null,
            asset = o.optString("asset"),
            amount = o.optDouble("amount", 0.0),
            profit = if (o.has("profit") && !o.isNull("profit")) o.optDouble("profit") else null,
            openPrice = o.optDouble("openPrice").takeUnless { it.isNaN() },
            closePrice = o.optDouble("closePrice").takeUnless { it.isNaN() },
            direction = when (cmd) { 0 -> Direction.CALL; 1 -> Direction.PUT; else -> null },
            openTimestamp = o.optLong("openTimestamp").takeIf { it > 0 },
            closeTimestamp = o.optLong("closeTimestamp").takeIf { it > 0 },
            isDemo = o.optInt("isDemo", 1) == 1,
        )
    }

    /** updateClosedDeals / updateOpenedDeals: [deal,...]; successcloseOrder: {"profit":x,"deals":[...]} */
    fun deals(payload: Any?): List<Deal> {
        val arr = when (payload) {
            is JSONArray -> payload
            is JSONObject -> payload.optJSONArray("deals") ?: return listOfNotNull(deal(payload))
            else -> return emptyList()
        }
        val out = ArrayList<Deal>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { deal(it) }?.let { out += it }
        return out
    }

    fun failedOrder(payload: Any?): FailedOrder? = when (payload) {
        is JSONObject -> FailedOrder(payload.optString("error", "unknown error"), payload.optString("asset"), payload.optDouble("amount", 0.0))
        is String -> FailedOrder(payload, "", 0.0)
        else -> null
    }

    /** Timestamps arrive as seconds (float) or milliseconds. Normalise to seconds. */
    fun normalizeTs(v: Double): Double = if (v > 1e12) v / 1000.0 else v
}

enum class TradeOutcome { WIN, LOSS, DRAW }

fun outcomeOf(profit: Double): TradeOutcome = when {
    profit > 0 -> TradeOutcome.WIN
    profit < 0 -> TradeOutcome.LOSS
    else -> TradeOutcome.DRAW
}

/**
 * Settle a closed deal into (outcome, net P/L).
 * Primary: price comparison (open vs close vs direction) when present.
 * Fallback: sign of `profit` (BinaryOptionsTools-v2 treats profit>0 win, 0 draw, <0 loss).
 * Net P/L guards against the server reporting a gross payout (stake included) instead of net:
 * payouts are always < 100%, so a winning `profit` larger than the stake must be gross.
 */
fun settle(deal: Deal): Pair<TradeOutcome, Double> {
    val op = deal.openPrice; val cp = deal.closePrice; val dir = deal.direction
    val byPrice: TradeOutcome? = if (op != null && cp != null && dir != null && op > 0 && cp > 0) {
        when {
            cp == op -> TradeOutcome.DRAW
            (cp > op) == (dir == Direction.CALL) -> TradeOutcome.WIN
            else -> TradeOutcome.LOSS
        }
    } else null
    val p = deal.profit
    val outcome = byPrice ?: p?.let { outcomeOf(it) } ?: TradeOutcome.DRAW
    val net = when (outcome) {
        TradeOutcome.WIN -> when {
            p == null || p <= 0 -> 0.0
            p > deal.amount -> p - deal.amount
            else -> p
        }
        TradeOutcome.LOSS -> if (p != null && p < 0) p else -deal.amount
        TradeOutcome.DRAW -> 0.0
    }
    return outcome to net
}
