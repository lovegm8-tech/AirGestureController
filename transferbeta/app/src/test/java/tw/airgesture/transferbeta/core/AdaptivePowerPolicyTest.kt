package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class AdaptivePowerPolicyTest {
    @Test fun balancedStandbyReducesInference() {
        val p = AdaptivePowerPolicy()
        p.start(); p.screenChanged(true)
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(0L))
        assertTrue(p.shouldInfer(0L))
        assertFalse(p.shouldInfer(1500L))
        assertTrue(p.shouldInfer(1550L))
    }

    @Test fun afterOneMinuteDeepIdleUsesLongerInterval() {
        val p = AdaptivePowerPolicy()
        p.start(); p.screenChanged(true)
        assertTrue(p.shouldInfer(0L))
        assertTrue(p.shouldInfer(60_000L))
        assertFalse(p.shouldInfer(62_000L))
        assertTrue(p.shouldInfer(62_100L))
    }

    @Test fun handImmediatelyRestoresFastInferenceWithoutIdleWait() {
        val p = AdaptivePowerPolicy()
        p.start(); p.screenChanged(true)
        assertTrue(p.shouldInfer(60_000L))
        p.observeHand(true, 61_000L)
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(61_000L))
        assertTrue(p.shouldInfer(61_000L))
        assertFalse(p.shouldInfer(61_110L))
        assertTrue(p.shouldInfer(61_115L)) // new hand: short recognition burst
        assertFalse(p.shouldInfer(61_165L))
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(63_200L))
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(63_201L))
    }

    @Test fun cheapSceneMotionWakesBalancedStandbyWithBoundedVerification() {
        val p = AdaptivePowerPolicy()
        p.start(); p.screenChanged(true)
        assertTrue(p.shouldSampleIdleMotion(0L))
        assertFalse(p.isDeepIdle(0L))
        assertTrue(p.shouldInfer(0L))
        // A scene change first requests a single early AI verification,
        // bounded to avoid duplicate inferences during successive frames.
        assertFalse(p.shouldInfer(100L, significantMotion = true))
        assertTrue(p.shouldInfer(240L, significantMotion = true))
        assertFalse(p.shouldInfer(380L, significantMotion = true))
        assertTrue(p.shouldInfer(480L, significantMotion = true))
        assertTrue(p.isDeepIdle(20_000L))
        p.observeHand(true, 20_240L)
        assertFalse(p.shouldSampleIdleMotion(20_241L))
        assertFalse(p.isDeepIdle(21_000L))
        p.screenChanged(false)
        assertFalse(p.shouldSampleIdleMotion(30_000L))
        assertFalse(p.shouldInfer(30_000L, significantMotion = true))
    }

    @Test fun shortMotionProbeTracksHandEnteringFrameThenReturnsToEco() {
        val p = AdaptivePowerPolicy()
        p.start(); p.screenChanged(true)
        assertTrue(p.shouldInfer(0L))
        assertTrue(p.shouldInfer(300L, significantMotion = true))
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(400L))
        assertTrue(p.shouldInfer(465L))
        assertTrue(p.shouldInfer(630L))
        assertTrue(p.shouldInfer(795L))
        // A non-hand motion hint is not a permanent ACTIVE state.
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(821L))
        assertFalse(p.shouldInfer(821L))
        p.screenChanged(false)
        assertEquals(AdaptivePowerPolicy.Mode.STOPPED, p.mode(822L))
    }

    @Test fun batteryFirstKeepsPreviousDeepIdleMotionWakeBehavior() {
        val p = AdaptivePowerPolicy()
        p.setPowerMode(0)
        p.start(); p.screenChanged(true)
        assertTrue(p.shouldInfer(0L))
        assertFalse(p.shouldSampleIdleMotion(500L))
        assertFalse(p.shouldInfer(500L, significantMotion = true))
        assertTrue(p.shouldSampleIdleMotion(20_000L))
        assertTrue(p.shouldInfer(20_000L, significantMotion = true))
    }

    @Test fun powerModesTradeSpeedForEnergy() {
        val save = AdaptivePowerPolicy()
        save.setPowerMode(0)
        save.start(); save.screenChanged(true)
        assertTrue(save.shouldInfer(0L))
        assertFalse(save.shouldInfer(1800L))
        assertTrue(save.shouldInfer(1950L))
        save.observeHand(true, 2250L)
        assertTrue(save.shouldInfer(2250L))
        assertFalse(save.shouldInfer(2400L))
        assertTrue(save.shouldInfer(2470L))

        val quick = AdaptivePowerPolicy()
        quick.setPowerMode(2)
        quick.start(); quick.screenChanged(true)
        assertTrue(quick.shouldInfer(0L))
        assertTrue(quick.shouldInfer(950L))
        quick.observeHand(true, 1100L)
        assertTrue(quick.shouldInfer(1100L))
        assertFalse(quick.shouldInfer(1210L))
        assertTrue(quick.shouldInfer(1215L))
    }

    @Test fun receivingOfferDoesNotRequireWaitingForDeepIdle() {
        val p = AdaptivePowerPolicy()
        p.start(); p.screenChanged(true)
        assertTrue(p.shouldInfer(0L))
        p.setReceiverAwaiting(true)
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(1_000_000L))
        assertTrue(p.shouldInfer(1_000_000L))
        assertTrue(p.shouldInfer(1_000_165L))
        p.setReceiverAwaiting(false)
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(1_000_166L))
    }

    @Test fun afterBriefBurstSteadyHandReturnsToNormalActiveCadence() {
        val p = AdaptivePowerPolicy()
        p.start(); p.screenChanged(true)
        assertTrue(p.shouldInfer(0L))
        p.observeHand(true, 1500L)
        assertTrue(p.shouldInfer(1500L))
        // A visible hand permits a handful of extra classifications.
        assertFalse(p.shouldInfer(1610L))
        assertTrue(p.shouldInfer(1615L))
        p.observeHand(true, 1750L)
        assertTrue(p.shouldInfer(1750L))
        p.observeHand(true, 2150L)
        assertTrue(p.shouldInfer(2150L))
        // 900ms from first appearance has elapsed. Sustained hand should
        // not continuously re-arm the faster burst or run at 115ms forever.
        p.observeHand(true, 2450L)
        assertTrue(p.shouldInfer(2450L))
        assertFalse(p.shouldInfer(2580L))
        assertTrue(p.shouldInfer(2615L))
        // When the last hand has been absent long enough, a new hand gets a
        // NEW temporary burst rather than waiting on the full ECO interval.
        p.observeHand(true, 3200L)
        assertTrue(p.shouldInfer(3200L))
        assertFalse(p.shouldInfer(3300L))
        assertTrue(p.shouldInfer(3315L))
    }

    @Test fun noHandDoesNotIncreaseStandbyMlWorkload() {
        val p = AdaptivePowerPolicy()
        p.start(); p.screenChanged(true)
        assertTrue(p.shouldInfer(0L))
        assertFalse(p.shouldInfer(115L))
        assertFalse(p.shouldInfer(1000L))
        assertTrue(p.shouldInfer(1550L))
        p.screenChanged(false)
        assertFalse(p.shouldInfer(1700L))
        p.screenChanged(true)
        assertTrue(p.shouldInfer(1701L))
    }

    @Test fun screenOffStopsInferenceAndScreenOnResumes() {
        val p = AdaptivePowerPolicy()
        p.start(); p.screenChanged(true)
        p.setReceiverAwaiting(true)
        assertTrue(p.shouldInfer(100L))
        p.screenChanged(false)
        assertEquals(AdaptivePowerPolicy.Mode.STOPPED, p.mode(200L))
        assertFalse(p.shouldInfer(201L))
        p.screenChanged(true)
        assertTrue(p.shouldInfer(202L))
        p.stop()
        assertEquals(AdaptivePowerPolicy.Mode.STOPPED, p.mode(203L))
    }
}
