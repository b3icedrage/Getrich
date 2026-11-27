package com.pockettrader.app

import com.pockettrader.app.analytics.BacktestEngine
import com.pockettrader.app.protocol.Candle
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestEngineTest {
    @Test fun insufficientHistoryReturnsNoTrades() {
        val result = BacktestEngine.compare((0 until 20).map { Candle(it.toLong(), 1.0, 1.0, 1.0, 1.0) })
        assertTrue(result.all { it.trades == 0 })
    }

    @Test fun backtestNeverUsesMoreTradesThanAvailableBars() {
        val candles = (0 until 120).map { i ->
            val p = 1.0 + i * 0.001 + kotlin.math.sin(i / 4.0) * 0.01
            Candle(i.toLong(), p, p, p + 0.002, p - 0.002)
        }
        val reports = BacktestEngine.compare(candles, 80.0, 2)
        assertTrue(reports.all { it.trades <= candles.size })
        assertTrue(reports.all { it.winRatePct in 0.0..100.0 })
    }
}
