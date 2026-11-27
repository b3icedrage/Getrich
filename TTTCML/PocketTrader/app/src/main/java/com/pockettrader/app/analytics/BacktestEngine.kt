package com.pockettrader.app.analytics

import com.pockettrader.app.protocol.Candle
import kotlin.math.abs
import kotlin.math.sqrt

enum class BacktestStrategy(val label: String) {
    EMA_CROSS("EMA 9/21 crossover"),
    RSI_REVERSION("RSI mean reversion"),
    BOLLINGER_REVERSION("Bollinger mean reversion")
}

data class BacktestTrade(val direction: Int, val entry: Double, val exit: Double, val resultUnits: Double)
data class BacktestReport(
    val strategy: BacktestStrategy,
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val winRatePct: Double,
    val netUnits: Double,
    val maxDrawdownUnits: Double,
    val profitFactor: Double
)

/**
 * Transparent OHLC close-to-close research simulation.
 * It is NOT a Pocket Option execution simulator: it omits OTC payout changes, spread,
 * slippage, exact expiry settlement rules and broker fills. Payout is a user-supplied
 * assumption and results are not a prediction of future performance.
 */
object BacktestEngine {
    fun compare(candles: List<Candle>, payoutPct: Double = 80.0, expiryBars: Int = 1): List<BacktestReport> =
        BacktestStrategy.entries.map { run(candles, it, payoutPct, expiryBars) }

    fun run(candles: List<Candle>, strategy: BacktestStrategy, payoutPct: Double = 80.0, expiryBars: Int = 1): BacktestReport {
        val safeExpiry = expiryBars.coerceIn(1, 20)
        val payout = payoutPct.coerceIn(1.0, 100.0) / 100.0
        if (candles.size < 40 + safeExpiry) return BacktestReport(strategy, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0)
        val closes = candles.map { it.close }
        val ema9 = ema(closes, 9); val ema21 = ema(closes, 21); val rsi = rsi(closes, 14)
        val deals = mutableListOf<BacktestTrade>()
        var equity = 0.0; var peak = 0.0; var maxDd = 0.0
        for (i in 30 until candles.size - safeExpiry) {
            val direction = when (strategy) {
                BacktestStrategy.EMA_CROSS -> when {
                    ema9[i] > ema21[i] && ema9[i - 1] <= ema21[i - 1] -> 1
                    ema9[i] < ema21[i] && ema9[i - 1] >= ema21[i - 1] -> -1
                    else -> 0
                }
                BacktestStrategy.RSI_REVERSION -> when {
                    rsi[i] < 30.0 -> 1
                    rsi[i] > 70.0 -> -1
                    else -> 0
                }
                BacktestStrategy.BOLLINGER_REVERSION -> {
                    val window = closes.subList(i - 19, i + 1)
                    val mean = window.average()
                    val sd = sqrt(window.sumOf { (it - mean) * (it - mean) } / window.size)
                    when {
                        closes[i] < mean - 2.0 * sd -> 1
                        closes[i] > mean + 2.0 * sd -> -1
                        else -> 0
                    }
                }
            }
            if (direction == 0) continue
            val entry = closes[i]; val exit = closes[i + safeExpiry]
            val pnl = when {
                abs(exit - entry) < 1e-12 -> 0.0
                (exit > entry) == (direction > 0) -> payout
                else -> -1.0
            }
            deals += BacktestTrade(direction, entry, exit, pnl)
            equity += pnl; peak = maxOf(peak, equity); maxDd = maxOf(maxDd, peak - equity)
        }
        val wins = deals.count { it.resultUnits > 0 }; val losses = deals.count { it.resultUnits < 0 }
        val grossWin = deals.filter { it.resultUnits > 0 }.sumOf { it.resultUnits }
        val grossLoss = -deals.filter { it.resultUnits < 0 }.sumOf { it.resultUnits }
        return BacktestReport(strategy, deals.size, wins, losses, deals.size - wins - losses,
            if (deals.isEmpty()) 0.0 else wins * 100.0 / deals.size,
            deals.sumOf { it.resultUnits }, maxDd,
            if (grossLoss == 0.0) if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0 else grossWin / grossLoss)
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        val k = 2.0 / (period + 1.0)
        val out = MutableList(values.size) { 0.0 }
        if (values.isEmpty()) return out
        out[0] = values[0]
        for (i in 1 until values.size) out[i] = values[i] * k + out[i - 1] * (1.0 - k)
        return out
    }

    private fun rsi(values: List<Double>, period: Int): List<Double> {
        val out = MutableList(values.size) { 50.0 }
        if (values.size <= period) return out
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        var avgGain = gain / period; var avgLoss = loss / period
        fun value(): Double = when {
            avgLoss == 0.0 && avgGain == 0.0 -> 50.0
            avgLoss == 0.0 -> 100.0
            else -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        }
        out[period] = value()
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            avgGain = (avgGain * (period - 1) + maxOf(d, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + maxOf(-d, 0.0)) / period
            out[i] = value()
        }
        return out
    }
}
