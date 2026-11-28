package com.pockettrader.app.signals

import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.protocol.Direction

/**
 * Closed-candle MTF entry gate: H1/M30 structure, M5 pullback and M1 trigger.
 * Scores and filters are technical rules, not a probability or promise of profit.
 */
object MultiTimeframeEntryRules {
    fun rejection(m1: List<Candle>, m5: List<Candle>, m30: List<Candle>, h1: List<Candle>, direction: Direction): String? {
        if (listOf(m1, m5, m30, h1).any { it.size < 55 }) return "waiting for M1/M5/M30/H1 history"
        fun aligned(candles: List<Candle>, period: Int): Int {
            val closes = candles.map { it.close }
            val ema = Indicators.ema(closes, period).last()
            if (!ema.isFinite()) return 0
            return when { closes.last() > ema -> 1; closes.last() < ema -> -1; else -> 0 }
        }
        val sign = if (direction == Direction.CALL) 1 else -1
        val h1Trend = aligned(h1, 50)
        val m30Trend = aligned(m30, 50)
        if (h1Trend != sign || m30Trend != sign) return "H1 and M30 EMA50 structure do not agree with the signal"

        val m5Closes = m5.map { it.close }
        val m5Ema = Indicators.ema(m5Closes, 20)
        val m5Start = (m5.size - 11).coerceAtLeast(0)
        val pullback = (m5Start until m5.lastIndex).any { i ->
            m5Ema[i].isFinite() && if (sign > 0) m5[i].low <= m5Ema[i] else m5[i].high >= m5Ema[i]
        }
        if (!pullback) return "no M5 pullback to EMA20"

        val m5Highs = m5.map { it.high }; val m5Lows = m5.map { it.low }
        val adx = Indicators.adx(m5Highs, m5Lows, m5Closes, 14).adx.last()
        if (!adx.isFinite() || adx < 16.0) return "M5 ADX below 16; structure is too weak"

        val closes = m1.map { it.close }
        val macd = Indicators.macd(closes).histogram
        val n = closes.lastIndex
        val crossed = (maxOf(1, n - 2)..n).any { i ->
            macd[i - 1].isFinite() && macd[i].isFinite() &&
                if (sign > 0) macd[i - 1] <= 0.0 && macd[i] > 0.0 else macd[i - 1] >= 0.0 && macd[i] < 0.0
        }
        if (!crossed) return "no fresh M1 MACD trigger"
        val candle = m1.last()
        val range = candle.high - candle.low
        if (range <= 0.0) return "M1 trigger candle has no range"
        val body = kotlin.math.abs(candle.close - candle.open)
        val adverseWick = if (sign > 0) minOf(candle.open, candle.close) - candle.low else candle.high - maxOf(candle.open, candle.close)
        if (body / range < 0.25 || adverseWick / range > 0.55) return "M1 candle is wick-dominated; avoid late entry"
        val atr = Indicators.atr(m1.map { it.high }, m1.map { it.low }, closes, 14).last()
        if (!atr.isFinite() || atr <= 0.0 || range > atr * 2.2) return "M1 volatility spike; wait for a calmer entry"
        return null
    }
}

/**
 * Crypto-only VWAP divergence and ATR envelope. Pocket Option's live candle volume is
 * a tick-count proxy, not verified exchange-traded volume; the output is labelled accordingly.
 */
object CryptoVwapAtrFilter {
    data class Result(val reason: String, val vwap: Double, val upperBand: Double, val lowerBand: Double)

    fun rejection(candles: List<Candle>, direction: Direction): Result? {
        if (candles.size < 35) return Result("waiting for at least 35 M1 candles", Double.NaN, Double.NaN, Double.NaN)
        val day = Math.floorDiv(candles.last().startSec, 86_400L)
        val session = candles.filter { Math.floorDiv(it.startSec, 86_400L) == day && it.volume > 0.0 }
        if (session.size < 30) return Result("insufficient positive tick-volume samples for VWAP proxy", Double.NaN, Double.NaN, Double.NaN)
        var weighted = 0.0
        var volume = 0.0
        val vwap = DoubleArray(candles.size) { Double.NaN }
        var activeDay = Long.MIN_VALUE
        for (i in candles.indices) {
            val c = candles[i]
            val d = Math.floorDiv(c.startSec, 86_400L)
            if (d != activeDay) { weighted = 0.0; volume = 0.0; activeDay = d }
            if (c.volume > 0.0) {
                val typical = (c.high + c.low + c.close) / 3.0
                weighted += typical * c.volume
                volume += c.volume
            }
            if (volume > 0.0) vwap[i] = weighted / volume
        }
        val n = candles.lastIndex
        val highs = candles.map { it.high }; val lows = candles.map { it.low }; val closes = candles.map { it.close }
        val atr = Indicators.atr(highs, lows, closes, 14)[n]
        val currentVwap = vwap[n]
        if (!atr.isFinite() || atr <= 0.0 || !currentVwap.isFinite()) return Result("VWAP or ATR is not ready", Double.NaN, Double.NaN, Double.NaN)
        val upper = currentVwap + 1.5 * atr
        val lower = currentVwap - 1.5 * atr
        if (closes[n] > upper || closes[n] < lower) return Result("price is outside the 1.5 ATR VWAP band", currentVwap, upper, lower)

        val start = (n - 30).coerceAtLeast(2)
        val pivots = (start..(n - 2)).filter { i ->
            vwap[i].isFinite() && vwap[i - 1].isFinite() && vwap[i + 1].isFinite() &&
                (if (direction == Direction.CALL) lows[i] <= lows[i - 1] && lows[i] < lows[i + 1]
                else highs[i] >= highs[i - 1] && highs[i] > highs[i + 1])
        }
        if (pivots.size < 2) return Result("waiting for two VWAP-comparable swing points", currentVwap, upper, lower)
        val a = pivots[pivots.size - 2]
        val b = pivots.last()
        val divergence = if (direction == Direction.CALL) {
            lows[b] < lows[a] && vwap[b] > vwap[a]
        } else {
            highs[b] > highs[a] && vwap[b] < vwap[a]
        }
        if (!divergence) return Result("no confirming price-versus-VWAP divergence", currentVwap, upper, lower)
        val reclaim = if (direction == Direction.CALL) closes[n] > candles[n].open else closes[n] < candles[n].open
        if (!reclaim) return Result("VWAP divergence has no confirming M1 close", currentVwap, upper, lower)
        return null
    }
}
