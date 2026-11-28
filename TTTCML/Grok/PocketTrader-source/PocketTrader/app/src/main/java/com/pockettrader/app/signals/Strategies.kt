package com.pockettrader.app.signals

import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.protocol.Direction

/**
 * Signal strategies adapted to fixed-expiry binary options: each strategy looks at the candles
 * that have just CLOSED and answers CALL / PUT / nothing for a trade entered at the open of the
 * next candle, held for the configured expiry. Strategy-based only: no win is guaranteed.
 *
 * Not implemented (impossible with binary options): Grid/DCA, statistical pairs trading,
 * funding-rate / basis arbitrage.
 */
enum class StrategyType(val label: String, val description: String) {
    AUTO_REGIME("Auto (regime by ADX)", "ADX>trend level: trend-following, then breakout. ADX<range level: mean reversion. In between: no trade."),
    TREND_MTF("Multi-timeframe trend", "Higher-TF EMA(200) sets the trend; lower-TF MACD or Supertrend cross after a pullback triggers the entry."),
    MEAN_REVERSION("Mean reversion (BB + RSI)", "Low touches/pierces lower Bollinger Band with RSI<30 = CALL; high touches upper band with RSI>70 = PUT."),
    BREAKOUT("Volatility breakout + volume", "Close outside the prior Donchian range with tick-volume > 1.5x its 20-candle average."),
    LIQUIDITY_SWEEP("Liquidity sweep reversal", "Wick beyond the recent swing high/low, then close back inside = trade the reversal."),
    EMA_RSI("EMA cross + RSI (simple)", "Fast EMA crosses slow EMA, confirmed by RSI on the same side of 50."),
}

enum class TrendTrigger { MACD, SUPERTREND }

data class StrategyConfig(
    val type: StrategyType = StrategyType.AUTO_REGIME,
    /** Lower (signal) timeframe in seconds: 60, 300 or 900. */
    val timeframeSec: Int = 60,
    /** Higher timeframe used by the trend filter, seconds (e.g. 900, 3600). */
    val higherTimeframeSec: Int = 900,
    /** Trade expiry in seconds. Default = one signal candle. */
    val expirySec: Int = 60,
    val htfEmaPeriod: Int = 200,
    val trendTrigger: TrendTrigger = TrendTrigger.MACD,
    val pullbackEma: Int = 20,
    val pullbackLookback: Int = 10,
    val macdFast: Int = 12, val macdSlow: Int = 26, val macdSignal: Int = 9,
    val supertrendPeriod: Int = 10, val supertrendMult: Double = 3.0,
    val bbPeriod: Int = 20, val bbK: Double = 2.0,
    val rsiPeriod: Int = 14, val rsiOversold: Double = 30.0, val rsiOverbought: Double = 70.0,
    val donchianPeriod: Int = 20, val volumeAvgPeriod: Int = 20, val volumeMult: Double = 1.5,
    val requireVolume: Boolean = true,
    val sweepLookback: Int = 20,
    val adxPeriod: Int = 14, val adxTrend: Double = 25.0, val adxRange: Double = 20.0,
    val emaFast: Int = 9, val emaSlow: Int = 21,
    /** Ignore assets whose payout is below this. */
    val minPayout: Int = 80,
)

data class Decision(val direction: Direction, val strategy: StrategyType, val reason: String)

object Strategies {

    fun evaluate(cfg: StrategyConfig, ltf: List<Candle>, htf: List<Candle>): Decision? = when (cfg.type) {
        StrategyType.AUTO_REGIME -> autoRegime(cfg, ltf, htf)
        StrategyType.TREND_MTF -> trendMtf(cfg, ltf, htf)
        StrategyType.MEAN_REVERSION -> meanReversion(cfg, ltf)
        StrategyType.BREAKOUT -> breakout(cfg, ltf)
        StrategyType.LIQUIDITY_SWEEP -> liquiditySweep(cfg, ltf)
        StrategyType.EMA_RSI -> emaRsi(cfg, ltf)
    }

    /** Last ADX value on the signal timeframe, or NaN if not enough data. */
    fun lastAdx(cfg: StrategyConfig, ltf: List<Candle>): Double {
        if (ltf.size <= cfg.adxPeriod * 2) return Double.NaN
        return Indicators.adx(ltf.map { it.high }, ltf.map { it.low }, ltf.map { it.close }, cfg.adxPeriod).adx.last()
    }

    fun autoRegime(cfg: StrategyConfig, ltf: List<Candle>, htf: List<Candle>): Decision? {
        val adx = lastAdx(cfg, ltf)
        if (adx.isNaN()) return null
        val d = when {
            adx > cfg.adxTrend -> trendMtf(cfg, ltf, htf) ?: breakout(cfg, ltf)
            adx < cfg.adxRange -> meanReversion(cfg, ltf)
            else -> null
        } ?: return null
        return d.copy(reason = "ADX %.1f -> %s".format(adx, d.reason))
    }

    /** Higher-TF trend: +1 if close above EMA, -1 below, 0 if not enough data. */
    fun htfTrend(cfg: StrategyConfig, htf: List<Candle>): Int {
        if (htf.size < cfg.htfEmaPeriod) return 0
        val closes = htf.map { it.close }
        val e = Indicators.ema(closes, cfg.htfEmaPeriod).last()
        if (e.isNaN()) return 0
        return if (closes.last() > e) 1 else if (closes.last() < e) -1 else 0
    }

    fun trendMtf(cfg: StrategyConfig, ltf: List<Candle>, htf: List<Candle>): Decision? {
        val trend = htfTrend(cfg, htf)
        if (trend == 0 || ltf.size < maxOf(cfg.macdSlow + cfg.macdSignal, cfg.pullbackEma, cfg.supertrendPeriod) + 2) return null
        val closes = ltf.map { it.close }
        val n = ltf.size
        // Pullback: within the last N candles price traded back to (or through) the LTF EMA.
        val e = Indicators.ema(closes, cfg.pullbackEma)
        val pulled = (maxOf(0, n - cfg.pullbackLookback - 1) until n - 1).any { i ->
            !e[i].isNaN() && if (trend > 0) ltf[i].low <= e[i] else ltf[i].high >= e[i]
        }
        if (!pulled) return null
        val crossed: Boolean = when (cfg.trendTrigger) {
            TrendTrigger.MACD -> {
                val m = Indicators.macd(closes, cfg.macdFast, cfg.macdSlow, cfg.macdSignal)
                val h0 = m.histogram[n - 2]; val h1 = m.histogram[n - 1]
                !h0.isNaN() && !h1.isNaN() && (if (trend > 0) h0 <= 0 && h1 > 0 else h0 >= 0 && h1 < 0)
            }
            TrendTrigger.SUPERTREND -> {
                val st = Indicators.supertrend(ltf.map { it.high }, ltf.map { it.low }, closes, cfg.supertrendPeriod, cfg.supertrendMult)
                val d0 = st.direction[n - 2]; val d1 = st.direction[n - 1]
                d0 != 0 && d1 == trend && d0 == -trend
            }
        }
        if (!crossed) return null
        val dir = if (trend > 0) Direction.CALL else Direction.PUT
        return Decision(dir, StrategyType.TREND_MTF,
            "HTF EMA${cfg.htfEmaPeriod} trend ${if (trend > 0) "up" else "down"}, pullback to EMA${cfg.pullbackEma}, ${cfg.trendTrigger.name} cross")
    }

    fun meanReversion(cfg: StrategyConfig, ltf: List<Candle>): Decision? {
        if (ltf.size < maxOf(cfg.bbPeriod, cfg.rsiPeriod + 1) + 1) return null
        val closes = ltf.map { it.close }
        val bb = Indicators.bollinger(closes, cfg.bbPeriod, cfg.bbK)
        val rsi = Indicators.rsi(closes, cfg.rsiPeriod).last()
        val c = ltf.last(); val up = bb.upper.last(); val lo = bb.lower.last()
        if (rsi.isNaN() || up.isNaN()) return null
        return when {
            c.low <= lo && rsi < cfg.rsiOversold -> Decision(Direction.CALL, StrategyType.MEAN_REVERSION, "Lower BB touch, RSI %.1f".format(rsi))
            c.high >= up && rsi > cfg.rsiOverbought -> Decision(Direction.PUT, StrategyType.MEAN_REVERSION, "Upper BB touch, RSI %.1f".format(rsi))
            else -> null
        }
    }

    fun breakout(cfg: StrategyConfig, ltf: List<Candle>): Decision? {
        val n = ltf.size
        if (n < maxOf(cfg.donchianPeriod, cfg.volumeAvgPeriod) + 1) return null
        val ch = Indicators.donchian(ltf.map { it.high }, ltf.map { it.low }, cfg.donchianPeriod)
        val c = ltf.last(); val up = ch.upper.last(); val lo = ch.lower.last()
        val dir = when { c.close > up -> Direction.CALL; c.close < lo -> Direction.PUT; else -> return null }
        val prevVol = ltf.subList(n - 1 - cfg.volumeAvgPeriod, n - 1).map { it.volume }
        val avg = prevVol.average()
        var volNote = "tick-volume n/a"
        if (cfg.requireVolume) {
            // Tick count is a proxy for volume (Pocket Option streams no traded volume).
            if (prevVol.any { it <= 0.0 } || avg <= 0.0) return null
            if (c.volume < cfg.volumeMult * avg) return null
            volNote = "tick-volume %.0f vs avg %.1f".format(c.volume, avg)
        }
        return Decision(dir, StrategyType.BREAKOUT, "Close ${if (dir == Direction.CALL) "above" else "below"} ${cfg.donchianPeriod}-candle range, $volNote")
    }

    fun liquiditySweep(cfg: StrategyConfig, ltf: List<Candle>): Decision? {
        val n = ltf.size
        if (n < cfg.sweepLookback + 1) return null
        val prior = ltf.subList(n - 1 - cfg.sweepLookback, n - 1)
        val swingHigh = prior.maxOf { it.high }; val swingLow = prior.minOf { it.low }
        val c = ltf.last()
        return when {
            c.high > swingHigh && c.close < swingHigh -> Decision(Direction.PUT, StrategyType.LIQUIDITY_SWEEP, "Swept swing high then closed back inside")
            c.low < swingLow && c.close > swingLow -> Decision(Direction.CALL, StrategyType.LIQUIDITY_SWEEP, "Swept swing low then closed back inside")
            else -> null
        }
    }

    fun emaRsi(cfg: StrategyConfig, ltf: List<Candle>): Decision? {
        if (ltf.size < maxOf(cfg.emaSlow, cfg.rsiPeriod + 1) + 2) return null
        val closes = ltf.map { it.close }
        val f = Indicators.ema(closes, cfg.emaFast); val s = Indicators.ema(closes, cfg.emaSlow)
        val r = Indicators.rsi(closes, cfg.rsiPeriod).last()
        val n = closes.size
        val upCross = f[n - 2] <= s[n - 2] && f[n - 1] > s[n - 1]
        val dnCross = f[n - 2] >= s[n - 2] && f[n - 1] < s[n - 1]
        return when {
            upCross && r > 50 && r < cfg.rsiOverbought -> Decision(Direction.CALL, StrategyType.EMA_RSI, "EMA${cfg.emaFast} crossed above EMA${cfg.emaSlow}, RSI %.1f".format(r))
            dnCross && r < 50 && r > cfg.rsiOversold -> Decision(Direction.PUT, StrategyType.EMA_RSI, "EMA${cfg.emaFast} crossed below EMA${cfg.emaSlow}, RSI %.1f".format(r))
            else -> null
        }
    }
}
