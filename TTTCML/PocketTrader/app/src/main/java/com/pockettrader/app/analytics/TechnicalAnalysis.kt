package com.pockettrader.app.analytics

import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.protocol.Direction
import com.pockettrader.app.signals.Indicators
import kotlin.math.max

data class IndicatorReading(val name: String, val value: String, val bias: Int, val note: String)
data class TechnicalSnapshot(
    val direction: Direction?,
    /** Confluence strength, not a probability or expected win rate. */
    val score: Int,
    val label: String,
    val summary: String,
    val readings: List<IndicatorReading>
)

/**
 * Closed-candle confluence using EMA 20/50, RSI 14, MACD, ADX/DI,
 * Bollinger position and five-candle momentum. The score is not a win probability.
 * Weak, mixed or incomplete evidence returns WAIT.
 */
fun analyseTechnicalMarket(candles: List<Candle>): TechnicalSnapshot {
    if (candles.size < 60) return TechnicalSnapshot(null, 0, "WAIT",
        "Need at least 60 closed candles before evaluating indicators.", emptyList())

    val closes = candles.map { it.close }
    val highs = candles.map { it.high }
    val lows = candles.map { it.low }
    val ema20 = Indicators.ema(closes, 20).last()
    val ema50 = Indicators.ema(closes, 50).last()
    val rsi = Indicators.rsi(closes, 14).last()
    val macd = Indicators.macd(closes).histogram
    val macdNow = macd.last()
    val macdPrev = macd[macd.lastIndex - 1]
    val adx = Indicators.adx(highs, lows, closes, 14)
    val adxNow = adx.adx.last()
    val plusDi = adx.plusDi.last()
    val minusDi = adx.minusDi.last()
    val bands = Indicators.bollinger(closes, 20, 2.0)
    val upper = bands.upper.last()
    val lower = bands.lower.last()
    val middle = bands.middle.last()
    val last = closes.last()
    val momentum = last - closes[closes.lastIndex - 5]

    if (listOf(ema20, ema50, rsi, macdNow, macdPrev, adxNow, plusDi, minusDi, upper, lower, middle).any { !it.isFinite() }) {
        return TechnicalSnapshot(null, 0, "WAIT", "Indicator history is not ready yet. Wait for more closed candles.", emptyList())
    }

    val votes = mutableListOf<Int>()
    val readings = mutableListOf<IndicatorReading>()
    fun add(name: String, value: String, bias: Int, note: String, weight: Int) {
        repeat(weight) { votes += bias }
        readings += IndicatorReading(name, value, bias, note)
    }

    val emaBias = when {
        last > ema20 && ema20 > ema50 -> 1
        last < ema20 && ema20 < ema50 -> -1
        else -> 0
    }
    add("EMA 20/50", "%.6f / %.6f".format(ema20, ema50), emaBias,
        when (emaBias) { 1 -> "Price and averages aligned bullish"; -1 -> "Price and averages aligned bearish"; else -> "Mixed or flat trend" }, 2)

    val rsiBias = when {
        rsi in 52.0..68.0 -> 1
        rsi in 32.0..48.0 -> -1
        else -> 0
    }
    add("RSI 14", "%.1f".format(rsi), rsiBias,
        when { rsi >= 70 -> "Overbought"; rsi <= 30 -> "Oversold"; rsiBias > 0 -> "Bullish momentum"; rsiBias < 0 -> "Bearish momentum"; else -> "Neutral / extreme zone" }, 1)

    val macdBias = when {
        macdNow > 0 && macdNow >= macdPrev -> 1
        macdNow < 0 && macdNow <= macdPrev -> -1
        else -> 0
    }
    add("MACD 12/26/9", "%.6f".format(macdNow), macdBias,
        when (macdBias) { 1 -> "Positive and strengthening"; -1 -> "Negative and weakening"; else -> "No clear confirmation" }, 2)

    val adxBias = when {
        adxNow < 18.0 -> 0
        plusDi > minusDi -> 1
        minusDi > plusDi -> -1
        else -> 0
    }
    add("ADX / DI", "%.1f · +DI %.1f / -DI %.1f".format(adxNow, plusDi, minusDi), adxBias,
        when { adxNow < 18 -> "Weak trend; avoid chasing"; adxNow < 25 -> "Developing directional bias"; else -> "Trend strength confirmed by ADX" }, 2)

    val bandBias = when {
        last > middle && last < upper -> 1
        last < middle && last > lower -> -1
        else -> 0
    }
    add("Bollinger 20,2", "L %.6f · M %.6f · U %.6f".format(lower, middle, upper), bandBias,
        when { last >= upper -> "At / above upper band; chase risk"; last <= lower -> "At / below lower band; reversal risk"; bandBias > 0 -> "Upper half of band"; bandBias < 0 -> "Lower half of band"; else -> "Near band midpoint" }, 1)

    val momentumBias = when { momentum > 0 -> 1; momentum < 0 -> -1; else -> 0 }
    add("5-candle momentum", "%+.6f".format(momentum), momentumBias,
        if (momentumBias > 0) "Recent closes rising" else if (momentumBias < 0) "Recent closes falling" else "Flat", 1)

    val positive = votes.count { it > 0 }
    val negative = votes.count { it < 0 }
    val total = votes.size.coerceAtLeast(1)
    val score = (max(positive, negative) * 100.0 / total).toInt()
    val aligned = max(positive, negative)
    val direction = when {
        positive > negative && positive >= 7 && positive - negative >= 4 -> Direction.CALL
        negative > positive && negative >= 7 && negative - positive >= 4 -> Direction.PUT
        else -> null
    }
    val label = when {
        direction == null -> "WAIT"
        score >= 80 -> "STRONG ${direction.label.uppercase()} BIAS"
        else -> "MODERATE ${direction.label.uppercase()} BIAS"
    }
    val summary = when {
        direction == null -> "Indicators are mixed or lack enough agreement. No trade recommendation."
        adxNow < 18 -> "Directional votes agree, but ADX shows a weak trend. Wait for clearer confirmation."
        else -> "${aligned} of ${total} weighted indicator votes agree. Confirm fresh quotes, payout and expiry before any order."
    }
    return TechnicalSnapshot(direction, score, label, summary, readings)
}
