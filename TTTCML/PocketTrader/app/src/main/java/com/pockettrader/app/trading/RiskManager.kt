package com.pockettrader.app.trading

data class RiskConfig(
    val stake: Double = 1.0,
    /** Auto stake as a percentage of the current broker balance. */
    val stakePercent: Double = 1.0,
    /** Do not auto-trade when the percentage amount is below this broker minimum. */
    val minimumStake: Double = 1.0,
    val maxTradesPerSession: Int = 10,
    /** Stop for the day once net P/L <= -dailyStopLoss (account currency). 0 = off. */
    val dailyStopLoss: Double = 10.0,
    /** Stop for the day once net P/L >= dailyTakeProfit. 0 = off. */
    val dailyTakeProfit: Double = 10.0,
    val maxConsecutiveLosses: Int = 3,
    /** Only one autotrader trade open at a time. */
    val maxOpenTrades: Int = 1,
    /** Skip a signal if it reaches the executor later than this after its entry time. */
    val maxSignalDelaySec: Int = 3,
)

data class RiskState(
    val dayKey: String = "",
    val sessionTrades: Int = 0,
    val dailyPnl: Double = 0.0,
    val consecutiveLosses: Int = 0,
    val openTrades: Int = 0,
)

/** Pure risk bookkeeping. All limits apply to trades placed by the autotrader. */
class RiskManager(var config: RiskConfig) {
    var state = RiskState()
        private set

    fun rollDay(dayKey: String) {
        if (state.dayKey != dayKey) state = state.copy(dayKey = dayKey, dailyPnl = 0.0, consecutiveLosses = 0)
    }

    fun resetSession() { state = state.copy(sessionTrades = 0, consecutiveLosses = 0) }

    /** null = allowed, otherwise the reason it is blocked. */
    fun blockReason(dayKey: String): String? {
        rollDay(dayKey)
        val c = config; val s = state
        return when {
            c.stake <= 0 -> "Stake must be above 0"
            s.sessionTrades >= c.maxTradesPerSession -> "Max trades per session reached (${c.maxTradesPerSession})"
            c.dailyStopLoss > 0 && s.dailyPnl <= -c.dailyStopLoss -> "Daily stop-loss hit (%.2f)".format(s.dailyPnl)
            c.dailyTakeProfit > 0 && s.dailyPnl >= c.dailyTakeProfit -> "Daily take-profit hit (%.2f)".format(s.dailyPnl)
            c.maxConsecutiveLosses > 0 && s.consecutiveLosses >= c.maxConsecutiveLosses -> "Max consecutive losses reached (${s.consecutiveLosses})"
            s.openTrades >= c.maxOpenTrades -> "A trade is still open"
            // Worst case: an open trade loses. Do not let the next stake breach the stop-loss.
            c.dailyStopLoss > 0 && s.dailyPnl - c.stake < -c.dailyStopLoss - 1e-9 -> "Next stake could exceed the daily stop-loss"
            else -> null
        }
    }

    fun onOpened() { state = state.copy(sessionTrades = state.sessionTrades + 1, openTrades = state.openTrades + 1) }

    /** Order rejected by the server: give back the slot. */
    fun onRejected() { state = state.copy(sessionTrades = maxOf(0, state.sessionTrades - 1), openTrades = maxOf(0, state.openTrades - 1)) }

    fun onClosed(netProfit: Double, dayKey: String) {
        rollDay(dayKey)
        state = state.copy(
            openTrades = maxOf(0, state.openTrades - 1),
            dailyPnl = state.dailyPnl + netProfit,
            consecutiveLosses = when {
                netProfit < 0 -> state.consecutiveLosses + 1
                netProfit > 0 -> 0
                else -> state.consecutiveLosses
            },
        )
    }

    fun isLate(entryAtSec: Long, serverNowSec: Double): Boolean = serverNowSec - entryAtSec > config.maxSignalDelaySec
}
