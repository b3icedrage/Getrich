package com.pockettrader.app.signals

import kotlin.math.*

/** Regimes inferred from a three-state discrete Hidden Markov Model of absolute returns. */
enum class MarketRegime { STABLE, NORMAL, CHAOTIC, INSUFFICIENT_DATA }

data class RegimeEstimate(
    val regime: MarketRegime,
    val confidence: Double,
    val stateProbabilities: List<Double>,
    val observations: Int,
)

data class KdeTailResult(
    val lowerTailProbability: Double,
    val upperTailProbability: Double,
    val sampleCount: Int,
) {
    val extremeTailProbability: Double get() = min(lowerTailProbability, upperTailProbability)
    val side: String get() = if (lowerTailProbability <= upperTailProbability) "LOWER" else "UPPER"
}

data class PairAssessment(
    val hedgeRatio: Double,
    val correlation: Double,
    val residualZScore: Double,
    val tail: KdeTailResult,
    val halfLifeCandles: Double?,
    val eligibleForMeanReversion: Boolean,
    val reason: String,
)

enum class ActionType { BUY, SELL, WAIT, NEUTRAL }

data class TradeActionVector(
    val action: ActionType,
    /** Model score, not a calibrated probability of winning. */
    val convictionScore: Int,
    /** Fraction of account equity, e.g. 0.005 = 0.5%. */
    val targetAllocation: Double,
    val riskHorizonCandles: Int,
    val regime: MarketRegime,
    val explanation: String,
)

enum class SpreadStatus { VERIFIED, UNVERIFIED, TOO_WIDE }

data class SpreadAssessment(
    val status: SpreadStatus,
    val actualSpread: Double?,
    val referenceSpread: Double?,
    val discrepancyRatio: Double?,
    val explanation: String,
)

/**
 * Lightweight on-device HMM. Observations are absolute log returns discretized into
 * low/medium/high volatility buckets. Baum-Welch estimates the transition/emission
 * probabilities; the hidden state with the lowest/highest expected bucket is labelled
 * STABLE/CHAOTIC. This is a volatility-regime model, not a pair-coupling classifier.
 */
object HiddenMarkovRegimeModel {
    private const val STATES = 3
    private const val SYMBOLS = 3

    fun classify(closes: List<Double>, iterations: Int = 12): RegimeEstimate {
        if (closes.size < 45 || closes.any { !it.isFinite() || it <= 0.0 }) {
            return RegimeEstimate(MarketRegime.INSUFFICIENT_DATA, 0.0, emptyList(), closes.size)
        }
        val returns = closes.zipWithNext { a, b -> abs(ln(b / a)) }
        val sorted = returns.sorted()
        val q1 = sorted[(sorted.size * 0.33).coerceIn(0, sorted.lastIndex)]
        val q2 = sorted[(sorted.size * 0.66).coerceIn(0, sorted.lastIndex)]
        val obs = returns.map { if (it <= q1) 0 else if (it <= q2) 1 else 2 }.toIntArray()
        if (q2 <= 1e-12) return RegimeEstimate(MarketRegime.STABLE, 1.0, listOf(1.0, 0.0, 0.0), obs.size)

        var trans = Array(STATES) { i -> DoubleArray(STATES) { j -> if (i == j) 0.82 else 0.09 } }
        var emit = Array(STATES) { i -> DoubleArray(SYMBOLS) { j -> if (i == j) 0.80 else 0.10 } }
        var start = DoubleArray(STATES) { 1.0 / STATES }

        repeat(iterations.coerceIn(1, 30)) {
            val (alpha, scales) = forward(obs, start, trans, emit)
            val beta = backward(obs, scales, trans, emit)
            val gamma = Array(obs.size) { t ->
                val row = DoubleArray(STATES) { s -> alpha[t][s] * beta[t][s] }
                normalize(row)
            }
            val xiSum = Array(STATES) { DoubleArray(STATES) }
            val gammaSum = Array(STATES) { DoubleArray(SYMBOLS) }
            val stateSum = DoubleArray(STATES)
            for (t in obs.indices) for (s in 0 until STATES) {
                gammaSum[s][obs[t]] += gamma[t][s]
                stateSum[s] += gamma[t][s]
                if (t < obs.lastIndex) for (j in 0 until STATES) {
                    xiSum[s][j] += alpha[t][s] * trans[s][j] * emit[j][obs[t + 1]] * beta[t + 1][j]
                }
            }
            start = gamma.first().map { max(it, 1e-6) }.toDoubleArray().also { normalize(it) }
            for (s in 0 until STATES) {
                val rowSum = xiSum[s].sum()
                if (rowSum > 1e-12) for (j in 0 until STATES) trans[s][j] = max(xiSum[s][j] / rowSum, 1e-5)
                normalize(trans[s])
                if (stateSum[s] > 1e-12) for (k in 0 until SYMBOLS) emit[s][k] = max(gammaSum[s][k] / stateSum[s], 1e-5)
                normalize(emit[s])
            }
        }
        val (alpha, _) = forward(obs, start, trans, emit)
        val posterior = alpha.last().also { normalize(it) }
        val expectedBucket = DoubleArray(STATES) { s -> emit[s].mapIndexed { i, p -> i * p }.sum() }
        val order = expectedBucket.indices.sortedBy { expectedBucket[it] }
        val state = posterior.indices.maxByOrNull { posterior[it] } ?: return RegimeEstimate(MarketRegime.INSUFFICIENT_DATA, 0.0, emptyList(), obs.size)
        val regime = when (state) {
            order.first() -> MarketRegime.STABLE
            order.last() -> MarketRegime.CHAOTIC
            else -> MarketRegime.NORMAL
        }
        return RegimeEstimate(regime, posterior[state], posterior.toList(), obs.size)
    }

    private fun forward(obs: IntArray, start: DoubleArray, trans: Array<DoubleArray>, emit: Array<DoubleArray>): Pair<Array<DoubleArray>, DoubleArray> {
        val a = Array(obs.size) { DoubleArray(STATES) }
        val scale = DoubleArray(obs.size)
        for (s in 0 until STATES) a[0][s] = start[s] * emit[s][obs[0]]
        scale[0] = max(a[0].sum(), 1e-300); for (s in 0 until STATES) a[0][s] /= scale[0]
        for (t in 1 until obs.size) {
            for (j in 0 until STATES) {
                var v = 0.0
                for (i in 0 until STATES) v += a[t - 1][i] * trans[i][j]
                a[t][j] = v * emit[j][obs[t]]
            }
            scale[t] = max(a[t].sum(), 1e-300)
            for (s in 0 until STATES) a[t][s] /= scale[t]
        }
        return a to scale
    }

    private fun backward(obs: IntArray, scale: DoubleArray, trans: Array<DoubleArray>, emit: Array<DoubleArray>): Array<DoubleArray> {
        val b = Array(obs.size) { DoubleArray(STATES) { 1.0 } }
        for (t in obs.lastIndex - 1 downTo 0) for (i in 0 until STATES) {
            var v = 0.0
            for (j in 0 until STATES) v += trans[i][j] * emit[j][obs[t + 1]] * b[t + 1][j]
            b[t][i] = v / scale[t + 1]
        }
        return b
    }

    private fun normalize(values: DoubleArray) {
        val sum = values.sum()
        if (sum > 0.0 && sum.isFinite()) for (i in values.indices) values[i] /= sum
    }
}

/** Gaussian-kernel CDF evaluated against a historical sample; no normality assumption. */
object KernelDensityTailEstimator {
    fun estimate(historicalResiduals: List<Double>, observedResidual: Double): KdeTailResult? {
        val sample = historicalResiduals.filter { it.isFinite() }
        if (sample.size < 25 || !observedResidual.isFinite()) return null
        val mean = sample.average()
        val sd = sqrt(sample.sumOf { (it - mean) * (it - mean) } / (sample.size - 1))
        if (sd <= 1e-12) return null
        val bandwidth = max(1.06 * sd * sample.size.pow(-0.2), sd * 0.05)
        val cdf = sample.sumOf { normalCdf((observedResidual - it) / bandwidth) } / sample.size
        return KdeTailResult(cdf.coerceIn(0.0, 1.0), (1.0 - cdf).coerceIn(0.0, 1.0), sample.size)
    }

    private fun normalCdf(x: Double): Double {
        val z = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val erf = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-z * z)
        return (0.5 * (1.0 + if (x >= 0) erf else -erf)).coerceIn(0.0, 1.0)
    }
}

/** Pair statistics require two synchronized close-price series; never substitute one asset for both. */
object PairAnalytics {
    fun evaluate(assetA: List<Double>, assetB: List<Double>, minCorrelation: Double = 0.70): PairAssessment? {
        val n = min(assetA.size, assetB.size)
        if (n < 60) return null
        val a = assetA.takeLast(n); val b = assetB.takeLast(n)
        if ((a + b).any { !it.isFinite() || it <= 0.0 }) return null
        val x = b.map { ln(it) }; val y = a.map { ln(it) }
        val mx = x.average(); val my = y.average()
        val vx = x.sumOf { (it - mx).pow(2) }
        val vy = y.sumOf { (it - my).pow(2) }
        if (vx <= 1e-12 || vy <= 1e-12) return null
        val cov = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val beta = cov / vx
        val corr = cov / sqrt(vx * vy)
        val residual = x.indices.map { y[it] - beta * x[it] }
        val mean = residual.average()
        val sd = sqrt(residual.sumOf { (it - mean).pow(2) } / (n - 1))
        if (sd <= 1e-12) return null
        val z = (residual.last() - mean) / sd
        val tail = KernelDensityTailEstimator.estimate(residual.dropLast(1), residual.last()) ?: return null
        val dx = residual.zipWithNext { prev, next -> next - prev }
        val lag = residual.dropLast(1)
        val lagMean = lag.average(); val dxMean = dx.average()
        val denom = lag.sumOf { (it - lagMean).pow(2) }
        val slope = if (denom > 1e-12) lag.indices.sumOf { (lag[it] - lagMean) * (dx[it] - dxMean) } / denom else 0.0
        val halfLife = if (slope < 0.0) -ln(2.0) / slope else null
        val eligible = abs(corr) >= minCorrelation && halfLife != null && halfLife in 1.0..120.0 && tail.extremeTailProbability <= 0.05
        val reason = when {
            abs(corr) < minCorrelation -> "Pair correlation below stability threshold"
            halfLife == null || halfLife !in 1.0..120.0 -> "Residual mean-reversion half-life is not reliable"
            tail.extremeTailProbability > 0.05 -> "Residual is not in a KDE tail zone"
            else -> "Statistical pair candidate; validate cointegration out of sample before trading"
        }
        return PairAssessment(beta, corr, z, tail, halfLife, eligible, reason)
    }
}

/** Conservative fractional-Kelly sizing for fixed-payout products; returns 0 when edge is absent. */
object AdaptiveRiskAllocator {
    fun allocationFraction(
        estimatedWinProbability: Double,
        payoutRate: Double,
        drawdownFraction: Double,
        maxAllocation: Double = 0.005,
    ): Double {
        if (!estimatedWinProbability.isFinite() || !payoutRate.isFinite() || payoutRate <= 0.0) return 0.0
        val p = estimatedWinProbability.coerceIn(0.0, 1.0)
        val q = 1.0 - p
        val fullKelly = (p * payoutRate - q) / payoutRate
        if (fullKelly <= 0.0) return 0.0
        val drawdownScale = (1.0 - drawdownFraction.coerceIn(0.0, 0.90)).coerceIn(0.10, 1.0)
        return (fullKelly * 0.25 * drawdownScale).coerceIn(0.0, maxAllocation.coerceIn(0.0, 0.01))
    }
}

/** OTC spread cannot be inferred from OHLC candles. Require real bid/ask and a validated benchmark. */
object OtcSpreadAnalytics {
    fun assess(bid: Double?, ask: Double?, rationalReferenceSpread: Double?): SpreadAssessment {
        if (bid == null || ask == null || rationalReferenceSpread == null ||
            !bid.isFinite() || !ask.isFinite() || !rationalReferenceSpread.isFinite() ||
            bid <= 0.0 || ask < bid || rationalReferenceSpread <= 0.0
        ) return SpreadAssessment(SpreadStatus.UNVERIFIED, null, null, null,
            "Bid/ask quotes or a validated spread benchmark are unavailable; no dealer-pricing edge assumed.")
        val actual = ask - bid
        val ratio = actual / rationalReferenceSpread
        val status = if (ratio > 1.5) SpreadStatus.TOO_WIDE else SpreadStatus.VERIFIED
        return SpreadAssessment(status, actual, rationalReferenceSpread, ratio,
            if (status == SpreadStatus.TOO_WIDE) "Quoted spread is over 1.5x the reference." else "Spread is within the configured reference.")
    }
}
