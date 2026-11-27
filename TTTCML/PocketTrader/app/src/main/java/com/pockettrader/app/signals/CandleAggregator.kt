package com.pockettrader.app.signals

import com.pockettrader.app.protocol.Candle

/**
 * Builds fixed-period candles from ticks. A candle is closed either by the first tick of the
 * next period or by the clock ([onClock]) so signals fire right at the boundary even when
 * the market is quiet.
 */
class CandleAggregator(val periodSec: Int, private val maxCandles: Int = 500) {
    private val closed = ArrayDeque<Candle>()
    private var current: Candle? = null

    val candles: List<Candle> get() = closed.toList()
    val forming: Candle? get() = current

    fun bucket(tsSec: Double): Long = (Math.floor(tsSec / periodSec).toLong()) * periodSec

    fun reset() { closed.clear(); current = null }

    /** Seed with historical candles (must be same period) and/or raw ticks. */
    fun seed(history: List<Candle>, ticks: List<Pair<Double, Double>> = emptyList()) {
        reset()
        history.sortedBy { it.startSec }.forEach { push(it) }
        // The newest history candle is usually still forming: keep updating it with ticks.
        if (closed.isNotEmpty()) current = closed.removeLast()
        ticks.forEach { (t, p) -> onTick(t, p) }
    }

    /** Returns the candle that was closed by this tick, if any. */
    fun onTick(tsSec: Double, price: Double): Candle? {
        val b = bucket(tsSec)
        val cur = current
        val last = closed.lastOrNull()
        if (last != null && b <= last.startSec) return null // late tick for an already-closed candle
        return when {
            cur == null -> { current = Candle(b, price, price, price, price); null }
            b == cur.startSec -> { current = cur.update(price); null }
            b > cur.startSec -> {
                push(cur)
                current = Candle(b, price, price, price, price)
                cur
            }
            else -> null
        }
    }

    /** Close the forming candle if its period has ended according to [nowSec] (server-aligned). */
    fun onClock(nowSec: Double): Candle? {
        val cur = current ?: return null
        if (nowSec >= cur.startSec + periodSec) {
            push(cur)
            // Next candle opens at last close price until a real tick arrives.
            val nb = bucket(nowSec)
            current = Candle(nb, cur.close, cur.close, cur.close, cur.close, 0.0)
            return cur
        }
        return null
    }

    private fun push(c: Candle) {
        val last = closed.lastOrNull()
        if (last != null && c.startSec <= last.startSec) {
            if (c.startSec == last.startSec) { closed.removeLast(); closed.addLast(c) }
            return
        }
        closed.addLast(c)
        while (closed.size > maxCandles) closed.removeFirst()
    }
}
