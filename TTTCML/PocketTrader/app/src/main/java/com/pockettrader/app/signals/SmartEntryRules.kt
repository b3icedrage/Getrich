package com.pockettrader.app.signals

import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.protocol.Direction

/**
 * Strict entry gate for automatic and TT signal publication.
 * Uses closed broker candles only. This is a rule filter, not a win-probability model.
 */
object SmartEntryRules {
    fun rejection(ltf: List<Candle>, htf: List<Candle>, direction: Direction): String? {
        if (ltf.size < 205 || htf.size < 205) return "waiting for 200-period trend history"
        val closes = ltf.map { it.close }
        val highs = ltf.map { it.high }
        val lows = ltf.map { it.low }
        val n = closes.lastIndex
        val ema200 = Indicators.ema(closes, 200)[n]
        val htfCloses = htf.map { it.close }
        val htfEma200 = Indicators.ema(htfCloses, 200).last()
        if (!ema200.isFinite() || !htfEma200.isFinite()) return "200 EMA is not ready"
        val bullish = direction == Direction.CALL
        if (bullish && !(closes[n] > ema200 && htfCloses.last() > htfEma200))
            return "price is not above both local and higher-timeframe EMA 200"
        if (!bullish && !(closes[n] < ema200 && htfCloses.last() < htfEma200))
            return "price is not below both local and higher-timeframe EMA 200"

        val macd = Indicators.macd(closes)
        val hist = macd.histogram
        val crossRecent = (maxOf(1, n - 2)..n).any { i ->
            if (bullish) hist[i - 1].isFinite() && hist[i].isFinite() && hist[i - 1] <= 0.0 && hist[i] > 0.0
            else hist[i - 1].isFinite() && hist[i].isFinite() && hist[i - 1] >= 0.0 && hist[i] < 0.0
        }
        if (!crossRecent) return "no recent MACD directional crossover"

        val rsi = Indicators.rsi(closes, 14)
        val pivots = (n - 35).coerceAtLeast(2)..(n - 3).coerceAtLeast(2)
        var divergence = false
        val swingPoints = pivots.filter { i ->
            if (bullish) lows[i] <= lows[i - 1] && lows[i] < lows[i + 1]
            else highs[i] >= highs[i - 1] && highs[i] > highs[i + 1]
        }.filter { rsi[it].isFinite() }
        if (swingPoints.size >= 2) {
            val a = swingPoints[swingPoints.size - 2]
            val b = swingPoints.last()
            divergence = if (bullish) lows[b] < lows[a] && rsi[b] > rsi[a] + 1.0 && rsi[n] <= 45.0
                else highs[b] > highs[a] && rsi[b] < rsi[a] - 1.0 && rsi[n] >= 55.0
        }
        if (!divergence) return "no confirming RSI divergence"

        val atr = Indicators.atr(highs, lows, closes, 14)[n]
        if (!atr.isFinite() || atr <= 0.0) return "ATR support/resistance check is not ready"
        val lookbackStart = (n - 20).coerceAtLeast(0)
        val support = lows.subList(lookbackStart, n).minOrNull() ?: return "support/resistance history missing"
        val resistance = highs.subList(lookbackStart, n).maxOrNull() ?: return "support/resistance history missing"
        val candle = ltf[n]
        val rejectionAtLevel = if (bullish) {
            candle.low <= support + atr * 0.35 && candle.close > candle.open && candle.close > ltf[n - 1].close
        } else {
            candle.high >= resistance - atr * 0.35 && candle.close < candle.open && candle.close < ltf[n - 1].close
        }
        if (!rejectionAtLevel) return "no support/resistance rejection or pullback confirmation"
        return null
    }
}
