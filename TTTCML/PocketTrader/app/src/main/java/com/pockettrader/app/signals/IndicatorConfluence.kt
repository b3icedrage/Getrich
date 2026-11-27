package com.pockettrader.app.signals

import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.protocol.Direction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic indicator confluence. Scores are rule-based agreement scores, NOT
 * probabilities of winning. Uses closed candles only; weak/contradictory data returns WAIT.
 */
data class ConfluenceReport(
    val callScore: Int,
    val putScore: Int,
    val recommendation: Direction?,
    val regime: String,
    val volatilityPct: Double?,
    val summary: String,
    val components: List<String>,
)

object IndicatorConfluence {
    fun analyze(ltf: List<Candle>, htf: List<Candle>, minScore: Int = 60, minEdge: Int = 15): ConfluenceReport {
        val empty = ConfluenceReport(0, 0, null, "INSUFFICIENT DATA", null,
            "WAIT · need more closed candles", emptyList())
        if (ltf.size < 55) return empty
        val c = ltf.map { it.close }
        if (c.any { !it.isFinite() || it <= 0.0 }) return empty
        val h = ltf.map { it.high }; val l = ltf.map { it.low }
        val n = c.lastIndex
        var call = 0.0; var put = 0.0
        val notes = mutableListOf<String>()
        fun vote(direction: Int, weight: Double, note: String) {
            if (direction > 0) call += weight else if (direction < 0) put += weight
            notes += "$note: ${if (direction > 0) "bullish" else if (direction < 0) "bearish" else "neutral"}"
        }

        val e9 = Indicators.ema(c, 9); val e21 = Indicators.ema(c, 21); val e50 = Indicators.ema(c, 50)
        val emaDir = when {
            c[n] > e9[n] && e9[n] > e21[n] && e21[n] > e50[n] -> 1
            c[n] < e9[n] && e9[n] < e21[n] && e21[n] < e50[n] -> -1
            else -> 0
        }
        vote(emaDir, 22.0, "EMA 9/21/50 alignment")

        val rsi = Indicators.rsi(c, 14)[n]
        val rsiDir = when { rsi in 52.0..68.0 -> 1; rsi in 32.0..48.0 -> -1; else -> 0 }
        vote(rsiDir, 12.0, "RSI 14 (${"%.1f".format(rsi)})")

        val macd = Indicators.macd(c)
        val hist = macd.histogram
        val macdDir = when {
            hist[n].isFinite() && hist[n] > 0 && hist[n] >= hist[n - 1] -> 1
            hist[n].isFinite() && hist[n] < 0 && hist[n] <= hist[n - 1] -> -1
            else -> 0
        }
        vote(macdDir, 18.0, "MACD momentum")

        val adx = Indicators.adx(h, l, c, 14)
        val adxValue = adx.adx[n]
        val dmiDir = when {
            adxValue.isFinite() && adxValue >= 18 && adx.plusDi[n] > adx.minusDi[n] -> 1
            adxValue.isFinite() && adxValue >= 18 && adx.minusDi[n] > adx.plusDi[n] -> -1
            else -> 0
        }
        vote(dmiDir, 14.0, "ADX/DMI (${if (adxValue.isFinite()) "%.1f".format(adxValue) else "n/a"})")

        val st = Indicators.supertrend(h, l, c)
        val stDir = st.direction[n]
        vote(stDir, 14.0, "Supertrend")

        val bb = Indicators.bollinger(c, 20, 2.0)
        val width = bb.upper[n] - bb.lower[n]
        val pos = if (width > 0) (c[n] - bb.lower[n]) / width else 0.5
        val bbDir = when { pos > 0.55 && pos < 0.95 -> 1; pos < 0.45 && pos > 0.05 -> -1; else -> 0 }
        vote(bbDir, 8.0, "Bollinger position")

        val htfCloses = htf.map { it.close }
        val htfDir = if (htfCloses.size >= 50) {
            val htfEma = Indicators.ema(htfCloses, 50).last()
            when { htfCloses.last() > htfEma -> 1; htfCloses.last() < htfEma -> -1; else -> 0 }
        } else 0
        vote(htfDir, 12.0, "Higher-timeframe EMA 50")

        val atr = Indicators.atr(h, l, c, 14)[n]
        val volPct = if (atr.isFinite()) atr / c[n] * 100.0 else null
        val regime = when {
            adxValue.isFinite() && adxValue >= 25 -> "TRENDING"
            adxValue.isFinite() && adxValue < 18 -> "RANGING"
            else -> "MIXED"
        }
        val rawTotal = 100.0
        val callScore = (call / rawTotal * 100.0).toInt().coerceIn(0, 100)
        val putScore = (put / rawTotal * 100.0).toInt().coerceIn(0, 100)
        val best = max(callScore, putScore)
        val edge = abs(callScore - putScore)
        val direction = when {
            best < minScore || edge < minEdge -> null
            callScore > putScore -> Direction.CALL
            else -> Direction.PUT
        }
        val summary = if (direction == null) "WAIT · mixed or weak indicator agreement"
            else "${direction.label} bias · $best/100 confluence · $regime"
        return ConfluenceReport(callScore, putScore, direction, regime, volPct, summary, notes)
    }
}
