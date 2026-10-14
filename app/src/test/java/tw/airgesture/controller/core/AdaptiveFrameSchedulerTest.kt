package tw.airgesture.controller.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveFrameSchedulerTest {
    @Test fun balancedIdleFivePerSecond() {
        val scheduler = AdaptiveFrameScheduler()
        assertTrue(scheduler.shouldAnalyze(0L))
        scheduler.onAnalyzed(0L, false)
        assertFalse(scheduler.shouldAnalyze(199L))
        assertTrue(scheduler.shouldAnalyze(200L))
    }

    @Test fun balancesSpeedWhenHandAppears() {
        val scheduler = AdaptiveFrameScheduler()
        assertTrue(scheduler.shouldAnalyze(0L))
        scheduler.onAnalyzed(0L, false)
        assertTrue(scheduler.shouldAnalyze(200L))
        scheduler.onAnalyzed(200L, true)
        assertFalse(scheduler.shouldAnalyze(299L))
        assertTrue(scheduler.shouldAnalyze(300L))
        scheduler.onAnalyzed(300L, true)
        assertTrue(scheduler.shouldAnalyze(400L))
    }

    @Test fun handHoldStopsPrematureRateDrop() {
        val scheduler = AdaptiveFrameScheduler()
        scheduler.onAnalyzed(0L, true)
        assertTrue(scheduler.shouldAnalyze(100L))
        scheduler.onAnalyzed(1100L, false)
        // Hand was last seen at 0ms. At 1200ms the slower idle period is restored.
        assertFalse(scheduler.shouldAnalyze(1200L))
        assertTrue(scheduler.shouldAnalyze(1300L))
    }

    @Test fun modeSelectionAndIdleRates() {
        assertEquals(AdaptiveFrameScheduler.Mode.BALANCED, AdaptiveFrameScheduler.Mode.fromIndex(10))
        val eco = AdaptiveFrameScheduler(AdaptiveFrameScheduler.Mode.SAVER)
        assertTrue(eco.shouldAnalyze(0L))
        eco.onAnalyzed(0L, false)
        assertFalse(eco.shouldAnalyze(332L))
        assertTrue(eco.shouldAnalyze(333L))
        val fast = AdaptiveFrameScheduler(AdaptiveFrameScheduler.Mode.RESPONSIVE)
        assertTrue(fast.shouldAnalyze(0L))
        fast.onAnalyzed(0L, false)
        assertTrue(fast.shouldAnalyze(110L))
    }

    @Test fun gracefullyRearmsAfterClockReset() {
        val scheduler = AdaptiveFrameScheduler()
        scheduler.onAnalyzed(1000L, true)
        assertTrue(scheduler.shouldAnalyze(10L))
        scheduler.onAnalyzed(10L, false)
        assertFalse(scheduler.shouldAnalyze(109L))
    }
}
