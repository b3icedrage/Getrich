package com.pockettrader.app.signals

object Indicators {
    /** Exponential moving average series (seeded with the SMA of the first [period] values). NaN until ready. */
    fun ema(values: List<Double>, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (period <= 0 || values.size < period) return out
        val k = 2.0 / (period + 1)
        var prev = values.subList(0, period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder RSI series. NaN until [period]+1 values are available. */
    fun rsi(values: List<Double>, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (period <= 0 || values.size <= period) return out
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        var avgG = gain / period; var avgL = loss / period
        out[period] = rsiOf(avgG, avgL)
        for (i in period + 1 until values.size) {
            val d = values[i] - values[i - 1]
            avgG = (avgG * (period - 1) + maxOf(d, 0.0)) / period
            avgL = (avgL * (period - 1) + maxOf(-d, 0.0)) / period
            out[i] = rsiOf(avgG, avgL)
        }
        return out
    }

    private fun rsiOf(g: Double, l: Double): Double = when {
        l == 0.0 && g == 0.0 -> 50.0
        l == 0.0 -> 100.0
        else -> 100.0 - 100.0 / (1.0 + g / l)
    }

    fun sma(values: List<Double>, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (period <= 0) return out
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    data class Bands(val middle: DoubleArray, val upper: DoubleArray, val lower: DoubleArray)

    /** Bollinger Bands: SMA(period) +/- k * population standard deviation. */
    fun bollinger(values: List<Double>, period: Int = 20, k: Double = 2.0): Bands {
        val mid = sma(values, period)
        val up = DoubleArray(values.size) { Double.NaN }
        val lo = DoubleArray(values.size) { Double.NaN }
        for (i in period - 1 until values.size) {
            if (i < 0) continue
            var v = 0.0
            for (j in i - period + 1..i) { val d = values[j] - mid[i]; v += d * d }
            val sd = Math.sqrt(v / period)
            up[i] = mid[i] + k * sd; lo[i] = mid[i] - k * sd
        }
        return Bands(mid, up, lo)
    }

    data class Macd(val macd: DoubleArray, val signal: DoubleArray, val histogram: DoubleArray)

    fun macd(values: List<Double>, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): Macd {
        val ef = ema(values, fast); val es = ema(values, slow)
        val m = DoubleArray(values.size) { i -> if (ef[i].isNaN() || es[i].isNaN()) Double.NaN else ef[i] - es[i] }
        val first = m.indexOfFirst { !it.isNaN() }
        val sig = DoubleArray(values.size) { Double.NaN }
        if (first >= 0) {
            val s = ema(m.toList().subList(first, m.size), signalPeriod)
            for (i in s.indices) sig[first + i] = s[i]
        }
        val h = DoubleArray(values.size) { i -> if (m[i].isNaN() || sig[i].isNaN()) Double.NaN else m[i] - sig[i] }
        return Macd(m, sig, h)
    }

    fun trueRange(h: List<Double>, l: List<Double>, c: List<Double>): DoubleArray =
        DoubleArray(c.size) { i ->
            if (i == 0) h[0] - l[0]
            else maxOf(h[i] - l[i], Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1]))
        }

    /** Wilder ATR. */
    fun atr(h: List<Double>, l: List<Double>, c: List<Double>, period: Int = 14): DoubleArray {
        val tr = trueRange(h, l, c)
        val out = DoubleArray(c.size) { Double.NaN }
        if (c.size < period) return out
        var a = tr.take(period).average()
        out[period - 1] = a
        for (i in period until c.size) { a = (a * (period - 1) + tr[i]) / period; out[i] = a }
        return out
    }

    data class Adx(val adx: DoubleArray, val plusDi: DoubleArray, val minusDi: DoubleArray)

    /** Wilder ADX / +DI / -DI. ADX is defined from index 2*period-1. */
    fun adx(h: List<Double>, l: List<Double>, c: List<Double>, period: Int = 14): Adx {
        val n = c.size
        val adx = DoubleArray(n) { Double.NaN }; val pdi = DoubleArray(n) { Double.NaN }; val mdi = DoubleArray(n) { Double.NaN }
        if (n <= period * 2) return Adx(adx, pdi, mdi)
        val tr = trueRange(h, l, c)
        val pdm = DoubleArray(n); val mdm = DoubleArray(n)
        for (i in 1 until n) {
            val up = h[i] - h[i - 1]; val dn = l[i - 1] - l[i]
            pdm[i] = if (up > dn && up > 0) up else 0.0
            mdm[i] = if (dn > up && dn > 0) dn else 0.0
        }
        var sTr = 0.0; var sP = 0.0; var sM = 0.0
        for (i in 1..period) { sTr += tr[i]; sP += pdm[i]; sM += mdm[i] }
        val dx = DoubleArray(n) { Double.NaN }
        fun di(i: Int) {
            pdi[i] = if (sTr == 0.0) 0.0 else 100 * sP / sTr
            mdi[i] = if (sTr == 0.0) 0.0 else 100 * sM / sTr
            val s = pdi[i] + mdi[i]
            dx[i] = if (s == 0.0) 0.0 else 100 * Math.abs(pdi[i] - mdi[i]) / s
        }
        di(period)
        for (i in period + 1 until n) {
            sTr = sTr - sTr / period + tr[i]; sP = sP - sP / period + pdm[i]; sM = sM - sM / period + mdm[i]
            di(i)
        }
        val firstAdx = 2 * period - 1
        var a = (period..firstAdx).map { dx[it] }.average()
        adx[firstAdx] = a
        for (i in firstAdx + 1 until n) { a = (a * (period - 1) + dx[i]) / period; adx[i] = a }
        return Adx(adx, pdi, mdi)
    }

    data class Channel(val upper: DoubleArray, val lower: DoubleArray)

    /**
     * Donchian channel of the *previous* [period] candles (excludes candle i), so a close
     * above upper[i] is a breakout of the prior range.
     */
    fun donchian(h: List<Double>, l: List<Double>, period: Int = 20): Channel {
        val n = h.size
        val up = DoubleArray(n) { Double.NaN }; val lo = DoubleArray(n) { Double.NaN }
        for (i in period until n) {
            var mx = Double.NEGATIVE_INFINITY; var mn = Double.POSITIVE_INFINITY
            for (j in i - period until i) { mx = maxOf(mx, h[j]); mn = minOf(mn, l[j]) }
            up[i] = mx; lo[i] = mn
        }
        return Channel(up, lo)
    }

    /** Supertrend direction (+1 up, -1 down, 0 not ready) and line. */
    data class Supertrend(val direction: IntArray, val line: DoubleArray)

    fun supertrend(h: List<Double>, l: List<Double>, c: List<Double>, period: Int = 10, mult: Double = 3.0): Supertrend {
        val n = c.size
        val dir = IntArray(n); val line = DoubleArray(n) { Double.NaN }
        val a = atr(h, l, c, period)
        var fu = Double.NaN; var fl = Double.NaN; var trend = 0
        for (i in 0 until n) {
            if (a[i].isNaN()) continue
            val mid = (h[i] + l[i]) / 2
            val bu = mid + mult * a[i]; val bl = mid - mult * a[i]
            if (trend == 0) {
                fu = bu; fl = bl; trend = if (c[i] >= mid) 1 else -1
            } else {
                val pc = c[i - 1]
                fu = if (bu < fu || pc > fu) bu else fu
                fl = if (bl > fl || pc < fl) bl else fl
                trend = if (trend == 1 && c[i] < fl) -1 else if (trend == -1 && c[i] > fu) 1 else trend
            }
            dir[i] = trend
            line[i] = if (trend == 1) fl else fu
        }
        return Supertrend(dir, line)
    }
}
