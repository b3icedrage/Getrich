package com.pockettrader.app.signals

import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.protocol.Direction
import java.util.concurrent.atomic.AtomicLong

data class Signal(
    val id: Long,
    val asset: String,
    val direction: Direction,
    val strategy: StrategyType,
    val reason: String,
    /** Close time of the candle that produced the signal = entry time (unix sec, server clock). */
    val entryAtSec: Long,
    val expirySec: Int,
    /** Wall-clock millis when the app emitted the signal. */
    val emittedAtMs: Long,
    /** Directional indicator-agreement score (0-100), not a win probability. */
    val confluenceScore: Int = 0,
    /** Broker price at signal emission; informational until an order is accepted. */
    val referencePrice: Double? = null,
)

/**
 * Feeds ticks into a signal-timeframe aggregator and a higher-timeframe aggregator and
 * evaluates the selected strategy exactly once per closed signal candle.
 */
class SignalEngine(cfg: StrategyConfig) {
    var config: StrategyConfig = cfg
        private set
    var minConfluenceScore: Int = 80
    var ltf = CandleAggregator(cfg.timeframeSec)
        private set
    var htf = CandleAggregator(cfg.higherTimeframeSec)
        private set
    /** Fixed context layers used by the M1 entry model. */
    var m5 = CandleAggregator(300)
        private set
    var m30 = CandleAggregator(1800)
        private set
    var isCryptoAsset: Boolean = false
    private var lastEvaluated = Long.MIN_VALUE
    private companion object { val globalSignalIds = AtomicLong(1L) }

    fun reconfigure(cfg: StrategyConfig) {
        val tfChanged = cfg.timeframeSec != config.timeframeSec || cfg.higherTimeframeSec != config.higherTimeframeSec
        config = cfg
        if (tfChanged) {
            ltf = CandleAggregator(cfg.timeframeSec); htf = CandleAggregator(cfg.higherTimeframeSec)
            lastEvaluated = Long.MIN_VALUE
        }
    }

    fun reset() { ltf.reset(); htf.reset(); m5.reset(); m30.reset(); lastEvaluated = Long.MIN_VALUE }

    /** Returns a signal when this tick closed a signal candle and the strategy fired. */
    fun onTick(asset: String, tsSec: Double, price: Double, nowMs: Long): Signal? {
        htf.onTick(tsSec, price)
        m5.onTick(tsSec, price)
        m30.onTick(tsSec, price)
        val closed = ltf.onTick(tsSec, price) ?: return null
        return evaluate(asset, closed, nowMs)
    }

    /** Clock-driven close so the signal is emitted at the boundary even with no new tick. */
    fun onClock(asset: String, serverNowSec: Double, nowMs: Long): Signal? {
        htf.onClock(serverNowSec)
        m5.onClock(serverNowSec)
        m30.onClock(serverNowSec)
        val closed = ltf.onClock(serverNowSec) ?: return null
        return evaluate(asset, closed, nowMs)
    }

    private fun evaluate(asset: String, closed: Candle, nowMs: Long): Signal? {
        if (closed.startSec <= lastEvaluated) return null
        lastEvaluated = closed.startSec
        val htfCandles = htf.candles
        val m5Candles = m5.candles
        val m30Candles = m30.candles
        // TT is a market-wide indicator scanner, not a filter on the currently
        // selected strategy's rare entry triggers. Evaluate every closed candle and
        // publish a directional confluence candidate whenever it clears the watch
        // floor and directional edge. TradingEngine applies stricter execution checks.
        val analysis = IndicatorConfluence.analyze(
            ltf.candles, htfCandles,
            minScore = (minConfluenceScore - 10).coerceAtLeast(40)
        )
        val direction = analysis.recommendation ?: return null
        val entryBlock = MultiTimeframeEntryRules.rejection(ltf.candles, m5Candles, m30Candles, htfCandles, direction)
        if (entryBlock != null) return null
        val cryptoNote = if (isCryptoAsset) {
            val cryptoBlock = CryptoVwapAtrFilter.rejection(ltf.candles, direction)
            if (cryptoBlock != null) return null
            " | Crypto tick-volume VWAP divergence + 1.5 ATR bands confirmed (VWAP is a proxy, not exchange volume)"
        } else ""
        val volatility = analysis.volatilityPct?.let { " · ATR %.3f%%".format(it) } ?: ""
        val reason = "MTF confirmed: H1 + M30 structure, M5 pullback, M1 trigger | ${analysis.summary} | CALL ${analysis.callScore}/100, PUT ${analysis.putScore}/100$volatility$cryptoNote"
        val score = if (direction == Direction.CALL) analysis.callScore else analysis.putScore
        return Signal(nowMs * 1000L + globalSignalIds.getAndIncrement().mod(1000L), asset, direction, config.type, reason,
            closed.startSec + config.timeframeSec, config.expirySec, nowMs, score, closed.close)
    }
}
