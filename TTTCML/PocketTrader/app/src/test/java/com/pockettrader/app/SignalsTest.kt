package com.pockettrader.app

import com.pockettrader.app.protocol.Candle
import com.pockettrader.app.protocol.Direction
import com.pockettrader.app.signals.*
import org.junit.Assert.*
import org.junit.Test

class SignalsTest {
    private fun c(i: Int, o: Double, h: Double, l: Double, cl: Double, v: Double = 10.0) = Candle(i * 60L, o, h, l, cl, v)
    private fun flat(n: Int, p: Double = 100.0, v: Double = 10.0) = (0 until n).map { i ->
        val w = if (i % 2 == 0) 0.2 else -0.2
        c(i, p, p + 0.5, p - 0.5, p + w, v)
    }

    @Test fun aggregatorClosesOnTickAndClock() {
        val a = CandleAggregator(60)
        assertNull(a.onTick(0.5, 1.0)); assertNull(a.onTick(30.0, 2.0)); assertNull(a.onTick(59.9, 0.5))
        val closed = a.onTick(60.1, 1.5)!!
        assertEquals(Candle(0, 1.0, 2.0, 0.5, 0.5, 3.0), closed)
        assertNull(a.onClock(119.0))
        val byClock = a.onClock(120.0)!!
        assertEquals(60L, byClock.startSec); assertEquals(1.5, byClock.close, 0.0)
        assertNull(a.onTick(100.0, 9.0)) // late tick ignored
        assertEquals(2, a.candles.size)
    }

    @Test fun signalEngineEmitsOncePerCandleWithEntryAtClose() {
        val cfg = StrategyConfig(type = StrategyType.LIQUIDITY_SWEEP, timeframeSec = 60, sweepLookback = 5, expirySec = 60)
        val e = SignalEngine(cfg)
        var t = 0.0
        // 6 quiet candles 99.5..100.5
        repeat(6) { i -> listOf(100.0, 100.5, 99.5, 100.0).forEach { p -> e.onTick("X", t, p, 0); t += 14.0 }; t = (i + 1) * 60.0 }
        // sweep candle: spike to 102 then close back at 100.2
        listOf(100.0, 102.0, 100.2).forEach { p -> assertNull(e.onTick("X", t, p, 0)); t += 15.0 }
        val sig = e.onClock("X", 7 * 60.0, 123L)
        assertNotNull(sig); sig!!
        assertEquals(Direction.PUT, sig.direction)
        assertEquals(7 * 60L, sig.entryAtSec)
        assertNull(e.onClock("X", 7 * 60.0 + 1, 124L))
    }

    @Test fun meanReversion() {
        val base = flat(30)
        val cfg = StrategyConfig(type = StrategyType.MEAN_REVERSION)
        assertNull(Strategies.meanReversion(cfg, base))
        // Strong drop pierces lower band and drives RSI < 30
        val drop = base + (0 until 6).map { i -> val p = 99.5 - i * 1.0; c(30 + i, p + 0.5, p + 0.6, p - 0.4, p) }
        val d = Strategies.meanReversion(cfg, drop)
        assertEquals(Direction.CALL, d?.direction)
        val pump = base + (0 until 6).map { i -> val p = 100.5 + i * 1.0; c(30 + i, p - 0.5, p + 0.4, p - 0.6, p) }
        assertEquals(Direction.PUT, Strategies.meanReversion(cfg, pump)?.direction)
    }

    @Test fun donchianMomentumRequiresBreakoutAndBothOscillatorsAligned() {
        val base = (0 until 65).map { i ->
            val p = 100.0 + i * 0.05
            c(i, p - 0.02, p + 0.10, p - 0.10, p)
        }
        val breakout = base + c(65, 103.2, 104.0, 103.1, 103.9)
        assertEquals(Direction.CALL, Strategies.donchianMomentum(breakout)?.direction)
        val noBreakout = base + c(65, 103.0, 103.2, 102.9, 103.0)
        assertNull(Strategies.donchianMomentum(noBreakout))
        val tooShort = base.take(50)
        assertNull(Strategies.donchianMomentum(tooShort))
    }

    @Test fun breakoutNeedsVolume() {
        val base = flat(25)
        val cfg = StrategyConfig(type = StrategyType.BREAKOUT)
        val brk = base + c(25, 100.0, 103.0, 100.0, 102.5, v = 30.0)
        assertEquals(Direction.CALL, Strategies.breakout(cfg, brk)?.direction)
        val lowVol = base + c(25, 100.0, 103.0, 100.0, 102.5, v = 12.0)
        assertNull(Strategies.breakout(cfg, lowVol))
        assertEquals(Direction.CALL, Strategies.breakout(cfg.copy(requireVolume = false), lowVol)?.direction)
        val dn = base + c(25, 100.0, 100.0, 97.0, 97.5, v = 30.0)
        assertEquals(Direction.PUT, Strategies.breakout(cfg, dn)?.direction)
        // unknown (0) volume in history -> no breakout when volume required
        assertNull(Strategies.breakout(cfg, flat(25, v = 0.0) + c(25, 100.0, 103.0, 100.0, 102.5, v = 30.0)))
    }

    @Test fun liquiditySweepBothSides() {
        val base = flat(25)
        val cfg = StrategyConfig()
        assertEquals(Direction.PUT, Strategies.liquiditySweep(cfg, base + c(25, 100.0, 101.5, 99.8, 100.1))?.direction)
        assertEquals(Direction.CALL, Strategies.liquiditySweep(cfg, base + c(25, 100.0, 100.2, 98.5, 99.9))?.direction)
        assertNull(Strategies.liquiditySweep(cfg, base + c(25, 100.0, 101.5, 99.8, 101.2))) // closed outside = breakout, not sweep
    }

    @Test fun trendMtfMacdPullback() {
        val cfg = StrategyConfig(type = StrategyType.TREND_MTF, htfEmaPeriod = 20, trendTrigger = TrendTrigger.MACD)
        val htf = (0 until 30).map { i -> val p = 90.0 + i * 0.5; c(i, p, p + 0.2, p - 0.2, p) } // uptrend, last close above EMA20
        // Uptrend, then pullback (MACD hist negative), then resume -> hist crosses above 0
        val closes = ArrayList<Double>()
        var p = 100.0
        repeat(40) { p += 0.3; closes += p }
        repeat(8) { p -= 0.4; closes += p }
        var fired: Decision? = null
        repeat(15) {
            p += 0.5; closes += p
            val ltf = closes.mapIndexed { i, x -> c(i, x - 0.1, x + 0.2, x - 0.2, x) }
            if (fired == null) fired = Strategies.trendMtf(cfg, ltf, htf)
        }
        assertEquals(Direction.CALL, fired?.direction)
        // Against higher-TF trend -> never a PUT from this uptrend filter
        val htfDown = htf.map { it.copy(close = 200.0 - it.close, open = 200.0 - it.open, high = 200.0 - it.low, low = 200.0 - it.high) }
        val ltf = closes.mapIndexed { i, x -> c(i, x - 0.1, x + 0.2, x - 0.2, x) }
        assertNotEquals(Direction.CALL, Strategies.trendMtf(cfg, ltf, htfDown)?.direction)
    }

    @Test fun trendMtfNeedsEnoughHigherTfHistory() {
        val cfg = StrategyConfig(type = StrategyType.TREND_MTF) // EMA 200
        assertEquals(0, Strategies.htfTrend(cfg, flat(50)))
    }

    @Test fun autoRegimeNoTradeInMiddleBand() {
        val cfg = StrategyConfig(type = StrategyType.AUTO_REGIME, adxTrend = 101.0, adxRange = -1.0)
        assertNull(Strategies.autoRegime(cfg, flat(60), flat(60)))
    }

    @Test fun autoRegimeUsesMeanReversionWhenRanging() {
        val base = flat(40)
        val drop = base + (0 until 6).map { i -> val p = 99.5 - i * 1.0; c(40 + i, p + 0.5, p + 0.6, p - 0.4, p) }
        val cfg = StrategyConfig(type = StrategyType.AUTO_REGIME, adxTrend = 101.0, adxRange = 100.0)
        val d = Strategies.autoRegime(cfg, drop, emptyList())
        assertEquals(StrategyType.MEAN_REVERSION, d?.strategy)
    }
}
