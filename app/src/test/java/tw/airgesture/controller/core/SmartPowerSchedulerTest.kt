package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class SmartPowerSchedulerTest {
    private fun smart() = AdaptiveFrameScheduler(AdaptiveFrameScheduler.Mode.BALANCED, smartPower = true)

    @Test fun earlyNoHandWaits350msRatherThanLegacy200ms() {
        val s = smart()
        assertTrue(s.shouldAnalyze(0))
        s.onAnalyzed(0, false)
        assertFalse(s.shouldAnalyze(200))
        assertFalse(s.shouldAnalyze(349))
        assertTrue(s.shouldAnalyze(350))
    }

    @Test fun sustainedNoHandGoesTo900msAfter20Seconds() {
        val s = smart()
        assertTrue(s.shouldAnalyze(0))
        s.onAnalyzed(0, false)
        // Simulate prior low-speed inference near the 20-second mark.
        s.onAnalyzed(19_800, false)
        assertFalse(s.shouldAnalyze(20_600))
        assertTrue(s.shouldAnalyze(20_700))
        s.onAnalyzed(20_700, false)
        assertFalse(s.shouldAnalyze(21_599))
        assertTrue(s.shouldAnalyze(21_600))
    }

    @Test fun handImmediatelyRestores100msRecognitionFor4500ms() {
        val s = smart()
        s.onAnalyzed(0, false)
        s.onAnalyzed(21_000, false)
        assertTrue(s.shouldAnalyze(21_900))
        s.onAnalyzed(21_900, true)
        assertFalse(s.shouldAnalyze(21_999))
        assertTrue(s.shouldAnalyze(22_000))
        s.onAnalyzed(22_000, false)
        assertTrue(s.shouldAnalyze(22_100))
        s.onAnalyzed(26_399, false)
        assertFalse(s.shouldAnalyze(26_400))
        assertTrue(s.shouldAnalyze(26_749))
    }

    @Test fun seeingAHandRestarts20SecondIdleGraceWindow() {
        val s = smart()
        s.onAnalyzed(0, false)
        s.onAnalyzed(23_000, true)
        s.onAnalyzed(27_600, false)
        // No hand within 4.5 seconds: enters short, initially responsive idle.
        assertFalse(s.shouldAnalyze(27_900))
        assertTrue(s.shouldAnalyze(27_950))
        s.onAnalyzed(27_950, false)
        assertTrue(s.shouldAnalyze(28_300))
    }

    @Test fun legacySchedulingUnaffectedWhenSwitchOff() {
        val s = AdaptiveFrameScheduler(AdaptiveFrameScheduler.Mode.BALANCED, smartPower = false)
        s.onAnalyzed(0, false)
        assertFalse(s.shouldAnalyze(199))
        assertTrue(s.shouldAnalyze(200))
        s.onAnalyzed(200, true)
        assertTrue(s.shouldAnalyze(300))
        s.onAnalyzed(2000, false)
        assertTrue(s.shouldAnalyze(2200))
    }

    @Test fun newSchedulerRecoversAfterMonotonicTimestampReset() {
        val s = smart()
        s.onAnalyzed(20_000, true)
        assertTrue(s.shouldAnalyze(0))
        s.onAnalyzed(0, false)
        assertFalse(s.shouldAnalyze(100))
        assertTrue(s.shouldAnalyze(350))
    }
}
