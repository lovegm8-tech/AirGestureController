package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class AdvancedPowerSavingTest {
    @Test fun deepIdleReducesFullAiToOncePer1500ms() {
        val scheduler = AdaptiveFrameScheduler(
            AdaptiveFrameScheduler.Mode.BALANCED, smartPower = true,
            motionStandby = true
        )
        assertTrue(scheduler.shouldAnalyze(0))
        scheduler.onAnalyzed(0, false)
        scheduler.onAnalyzed(19_800, false)
        assertTrue(scheduler.isDeepIdle(20_000))
        assertFalse(scheduler.shouldAnalyze(20_699))
        assertTrue(scheduler.shouldAnalyze(21_300))
        scheduler.onAnalyzed(21_300, false)
        assertFalse(scheduler.shouldAnalyze(22_799))
        assertTrue(scheduler.shouldAnalyze(22_800))
    }

    @Test fun realMotionCanWakeBeforeNextAiPeriodButNotEveryFrame() {
        val scheduler = AdaptiveFrameScheduler(
            AdaptiveFrameScheduler.Mode.BALANCED, smartPower = true,
            motionStandby = true
        )
        scheduler.onAnalyzed(0, false)
        scheduler.onAnalyzed(20_400, false)
        assertTrue(scheduler.isDeepIdle(20_700))
        assertTrue(scheduler.shouldAnalyze(20_700, significantMotion = true))
        scheduler.onAnalyzed(20_700, false)
        assertFalse(scheduler.shouldAnalyze(20_800, significantMotion = true))
        assertTrue(scheduler.shouldAnalyze(20_950, significantMotion = true))
    }

    @Test fun seeingAHandRestores100msAndStopsRunningSentinel() {
        val scheduler = AdaptiveFrameScheduler(
            AdaptiveFrameScheduler.Mode.BALANCED, smartPower = true,
            motionStandby = true
        )
        scheduler.onAnalyzed(0, false)
        scheduler.onAnalyzed(21_000, false)
        assertTrue(scheduler.isDeepIdle(21_900))
        scheduler.onAnalyzed(21_900, true)
        assertFalse(scheduler.isDeepIdle(22_000))
        assertTrue(scheduler.shouldAnalyze(22_000))
        scheduler.onAnalyzed(22_000, true)
        assertTrue(scheduler.shouldAnalyze(22_100))
    }

    @Test fun disablingAdvancedMotionKeepsOriginalSmart900ms() {
        val scheduler = AdaptiveFrameScheduler(
            AdaptiveFrameScheduler.Mode.BALANCED, smartPower = true,
            motionStandby = false
        )
        scheduler.onAnalyzed(0, false)
        scheduler.onAnalyzed(19_800, false)
        assertFalse(scheduler.isDeepIdle(21_000))
        assertFalse(scheduler.shouldAnalyze(20_699, significantMotion = true))
        assertTrue(scheduler.shouldAnalyze(20_700))
    }

    @Test fun disablingSmartPowerPreservesOriginalBalanced200ms() {
        val scheduler = AdaptiveFrameScheduler(
            AdaptiveFrameScheduler.Mode.BALANCED, smartPower = false,
            motionStandby = true
        )
        scheduler.onAnalyzed(0, false)
        assertFalse(scheduler.isDeepIdle(30_000))
        assertFalse(scheduler.shouldAnalyze(199))
        assertTrue(scheduler.shouldAnalyze(200))
    }

    @Test fun motionDetectorIgnoresUniformExposureChanges() {
        val detector = IdleMotionDetector()
        val base = IntArray(192) { 95 }
        assertFalse(detector.observe(base, 0))
        val uniform = IntArray(192) { 125 }
        assertFalse(detector.observe(uniform, 220))
    }

    @Test fun motionDetectorSeesLocalHandMotionWithoutPhotos() {
        val detector = IdleMotionDetector()
        val base = IntArray(192) { 95 }
        assertFalse(detector.observe(base, 0))
        val withHand = IntArray(192) { if (it < 50) 190 else 95 }
        assertTrue(detector.observe(withHand, 250))
        assertFalse(detector.observe(base, 500))  // cooldown
        assertFalse(detector.observe(base, 1750)) // baseline is already base
    }

    @Test fun detectorRejectsMinorNoiseAndClockReset() {
        val detector = IdleMotionDetector()
        val base = IntArray(192) { 105 }
        assertFalse(detector.observe(base, 1000))
        assertFalse(detector.observe(IntArray(192) { 108 }, 1180))
        assertFalse(detector.observe(IntArray(192) { 105 }, 10))
    }
}
