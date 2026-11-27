package com.pockettrader.app

import com.pockettrader.app.trading.RiskConfig
import com.pockettrader.app.trading.RiskManager
import org.junit.Assert.*
import org.junit.Test

class RiskManagerTest {
    private val day = "2026-11-27"

    @Test fun maxTradesAndOpenTrade() {
        val r = RiskManager(RiskConfig(stake = 1.0, maxTradesPerSession = 2, dailyStopLoss = 0.0, dailyTakeProfit = 0.0))
        assertNull(r.blockReason(day)); r.onOpened()
        assertEquals("A trade is still open", r.blockReason(day))
        r.onClosed(0.9, day); assertNull(r.blockReason(day)); r.onOpened(); r.onClosed(0.9, day)
        assertTrue(r.blockReason(day)!!.startsWith("Max trades"))
        r.resetSession(); assertNull(r.blockReason(day))
    }

    @Test fun stopLossTakeProfitAndStreak() {
        val r = RiskManager(RiskConfig(stake = 5.0, maxTradesPerSession = 100, dailyStopLoss = 12.0, dailyTakeProfit = 8.0, maxConsecutiveLosses = 3))
        r.onOpened(); r.onClosed(-5.0, day); r.onOpened(); r.onClosed(-5.0, day)
        // -10 now; another -5 would breach -12
        assertEquals("Next stake could exceed the daily stop-loss", r.blockReason(day))
        val tp = RiskManager(RiskConfig(stake = 1.0, dailyTakeProfit = 1.5, dailyStopLoss = 0.0))
        tp.onOpened(); tp.onClosed(0.9, day); tp.onOpened(); tp.onClosed(0.9, day)
        assertTrue(tp.blockReason(day)!!.startsWith("Daily take-profit"))
        val st = RiskManager(RiskConfig(stake = 1.0, maxConsecutiveLosses = 2, dailyStopLoss = 0.0, dailyTakeProfit = 0.0))
        st.onOpened(); st.onClosed(-1.0, day); st.onOpened(); st.onClosed(-1.0, day)
        assertTrue(st.blockReason(day)!!.startsWith("Max consecutive"))
        // new day resets daily P/L and streak
        assertNull(st.blockReason("2026-11-28"))
    }

    @Test fun rejectedOrderFreesSlotAndLateSignal() {
        val r = RiskManager(RiskConfig(maxTradesPerSession = 1, maxSignalDelaySec = 3))
        r.onOpened(); r.onRejected(); assertNull(r.blockReason(day))
        assertFalse(r.isLate(100, 102.5)); assertTrue(r.isLate(100, 103.5))
    }
    @Test fun reservationChecksAndConsumesOpenSlotAtomically() {
        val r = RiskManager(RiskConfig(maxOpenTrades = 1, maxTradesPerSession = 10, dailyStopLoss = 0.0, dailyTakeProfit = 0.0))
        assertNull(r.reserveOpen(day))
        assertEquals("A trade is still open", r.reserveOpen(day))
        assertEquals(1, r.state.openTrades)
        r.onRejected()
        assertNull(r.reserveOpen(day))
        assertEquals(1, r.state.sessionTrades)
        assertEquals(1, r.state.openTrades)
    }

}
