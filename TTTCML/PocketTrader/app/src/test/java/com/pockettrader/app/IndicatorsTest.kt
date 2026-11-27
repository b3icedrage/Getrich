package com.pockettrader.app

import com.pockettrader.app.signals.Indicators
import org.junit.Assert.*
import org.junit.Test

class IndicatorsTest {
    private val eps = 1e-6

    @Test fun smaAndEma() {
        val v = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val s = Indicators.sma(v, 3)
        assertTrue(s[1].isNaN()); assertEquals(2.0, s[2], eps); assertEquals(4.0, s[4], eps)
        val e = Indicators.ema(v, 3) // k=0.5, seed=2
        assertEquals(2.0, e[2], eps); assertEquals(3.0, e[3], eps); assertEquals(4.0, e[4], eps)
    }

    @Test fun rsiExtremesAndKnownValue() {
        val up = (1..20).map { it.toDouble() }
        assertEquals(100.0, Indicators.rsi(up, 14).last(), eps)
        val down = up.reversed()
        assertEquals(0.0, Indicators.rsi(down, 14).last(), eps)
        // Alternating +1/-1 -> RSI 50 at seed
        val alt = (0..14).map { if (it % 2 == 0) 10.0 else 11.0 }
        assertEquals(50.0, Indicators.rsi(alt, 14)[14], 1.0)
    }

    @Test fun bollinger() {
        val v = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0) // mean 5, pop sd 2
        val b = Indicators.bollinger(v, 8, 2.0)
        assertEquals(5.0, b.middle.last(), eps); assertEquals(9.0, b.upper.last(), eps); assertEquals(1.0, b.lower.last(), eps)
    }

    @Test fun macdOnLinearSeriesConverges() {
        val v = (1..200).map { it.toDouble() }
        val m = Indicators.macd(v)
        // For a linear ramp with slope 1: EMA lag = (n-1)/2 -> MACD = (26-1)/2 - (12-1)/2 = 7
        assertEquals(7.0, m.macd.last(), 1e-3)
        assertEquals(7.0, m.signal.last(), 1e-3)
        assertEquals(0.0, m.histogram.last(), 1e-3)
        assertTrue(m.macd[24].isNaN()); assertFalse(m.macd[25].isNaN())
    }

    @Test fun atrConstantRange() {
        val n = 30
        val h = List(n) { 11.0 }; val l = List(n) { 9.0 }; val c = List(n) { 10.0 }
        assertEquals(2.0, Indicators.atr(h, l, c, 14).last(), eps)
    }

    @Test fun adxStrongTrendVsRange() {
        val n = 80
        val c = (0 until n).map { 100.0 + it }
        val h = c.map { it + 0.5 }; val l = c.map { it - 0.5 }
        val a = Indicators.adx(h, l, c, 14)
        assertTrue(a.adx.last() > 90); assertTrue(a.plusDi.last() > a.minusDi.last())
        assertTrue(a.adx[26].isNaN()); assertFalse(a.adx[27].isNaN())
        // Choppy series -> low ADX
        val cc = (0 until n).map { 100.0 + if (it % 2 == 0) 1.0 else -1.0 }
        val ar = Indicators.adx(cc.map { it + 0.5 }, cc.map { it - 0.5 }, cc, 14)
        assertTrue("adx=${ar.adx.last()}", ar.adx.last() < 20)
    }

    @Test fun donchianExcludesCurrentCandle() {
        val h = listOf(5.0, 6.0, 7.0, 100.0); val l = listOf(1.0, 2.0, 3.0, 0.0)
        val ch = Indicators.donchian(h, l, 3)
        assertEquals(7.0, ch.upper[3], eps); assertEquals(1.0, ch.lower[3], eps); assertTrue(ch.upper[2].isNaN())
    }

    @Test fun supertrendFlips() {
        val up = (0 until 40).map { 100.0 + it }
        val down = (0 until 40).map { 139.0 - it * 2 }
        val c = up + down
        val st = Indicators.supertrend(c.map { it + 0.5 }, c.map { it - 0.5 }, c, 10, 3.0)
        assertEquals(1, st.direction[39]); assertEquals(-1, st.direction.last())
        assertEquals(0, st.direction[0])
        assertTrue(st.line[39] < c[39]); assertTrue(st.line.last() > c.last())
    }
}
