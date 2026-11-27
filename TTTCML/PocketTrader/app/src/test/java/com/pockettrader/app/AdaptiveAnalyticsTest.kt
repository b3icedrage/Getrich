package com.pockettrader.app

import com.pockettrader.app.signals.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AdaptiveAnalyticsTest {
    @Test fun hmmReturnsValidPosteriorAndFailsClosedOnShortHistory() {
        assertEquals(MarketRegime.INSUFFICIENT_DATA, HiddenMarkovRegimeModel.classify(List(20) { 100.0 + it }).regime)
        val closes = (0..100).fold(listOf(100.0)) { acc, i ->
            acc + acc.last() * exp(if (i % 2 == 0) 0.0004 else -0.0003)
        }
        val result = HiddenMarkovRegimeModel.classify(closes)
        assertTrue(result.confidence in 0.0..1.0)
        assertEquals(1.0, result.stateProbabilities.sum(), 1e-6)
        assertTrue(result.regime != MarketRegime.INSUFFICIENT_DATA)
    }

    @Test fun kdeRecognizesExtremeTailAndNeedsEnoughSamples() {
        val history = (0 until 80).map { sin(it * 0.7) * 0.01 }
        assertNull(KernelDensityTailEstimator.estimate(history.take(10), 0.5))
        val tail = KernelDensityTailEstimator.estimate(history, 0.5)!!
        assertTrue(tail.upperTailProbability < 0.01)
        assertEquals("UPPER", tail.side)
    }

    @Test fun pairAnalyticsRequiresTwoSeriesAndReportsCandidateOnlyWithFilters() {
        val b = (0..100).map { 1.0 + it * 0.001 + sin(it * 0.4) * 0.0001 }
        val a = b.mapIndexed { i, v -> v * exp(sin(i * 0.3) * 0.0002) }
        val result = PairAnalytics.evaluate(a, b)
        assertNotNull(result)
        assertTrue(result!!.correlation > 0.7)
        assertTrue(result.hedgeRatio.isFinite())
    }

    @Test fun allocatorNeverRisksWhenThereIsNoPositiveEdgeAndCapsExposure() {
        assertEquals(0.0, AdaptiveRiskAllocator.allocationFraction(0.40, 0.85, 0.0), 0.0)
        val allocation = AdaptiveRiskAllocator.allocationFraction(0.70, 0.85, 0.10)
        assertTrue(allocation > 0.0)
        assertTrue(allocation <= 0.005)
        assertTrue(AdaptiveRiskAllocator.allocationFraction(0.70, 0.85, 0.90) <= 0.0005)
    }

    @Test fun otcSpreadFailsClosedWithoutRealQuotesAndBenchmark() {
        val missing = OtcSpreadAnalytics.assess(null, null, null)
        assertEquals(SpreadStatus.UNVERIFIED, missing.status)
        val wide = OtcSpreadAnalytics.assess(1.0, 1.02, 0.01)
        assertEquals(SpreadStatus.TOO_WIDE, wide.status)
    }
}
