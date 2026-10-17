package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class AdaptivePowerPolicyTest {
    @Test fun initialStandbyRemainsResponsive() {
        val p = AdaptivePowerPolicy()
        p.start()
        p.screenChanged(true)
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(0L))
        assertTrue(p.shouldInfer(0L))
        assertFalse(p.shouldInfer(949L))
        assertTrue(p.shouldInfer(950L))
    }

    @Test fun afterOneMinuteWithoutHandDeepIdleReducesWork() {
        val p = AdaptivePowerPolicy()
        p.start()
        p.screenChanged(true)
        assertTrue(p.shouldInfer(0L))
        assertTrue(p.shouldInfer(60_000L))
        assertFalse(p.shouldInfer(61_000L))
        assertTrue(p.shouldInfer(61_250L))
    }

    @Test fun detectedHandInstantlySwitchesToFastMode() {
        val p = AdaptivePowerPolicy()
        p.start()
        p.screenChanged(true)
        assertTrue(p.shouldInfer(60_000L))
        p.observeHand(true, 61_000L)
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(61_000L))
        assertTrue(p.shouldInfer(61_000L))
        assertFalse(p.shouldInfer(61_100L))
        assertTrue(p.shouldInfer(61_115L))
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(63_200L))
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(63_201L))
    }

    @Test fun incomingFileKeepsReceivingHandFastDuringThirtySecondOffer() {
        val p = AdaptivePowerPolicy()
        p.start()
        p.screenChanged(true)
        p.shouldInfer(0L)
        p.setReceiverAwaiting(true)
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(1_000_000L))
        assertTrue(p.shouldInfer(1_000_000L))
        assertTrue(p.shouldInfer(1_000_115L))
        p.setReceiverAwaiting(false)
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(1_000_116L))
    }

    @Test fun screenOffReleasesInferenceEvenWhileOfferAwaits() {
        val p = AdaptivePowerPolicy()
        p.start()
        p.screenChanged(true)
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

    @Test fun idleTimerRestartsOnNewScreenSessionAndFreshHand() {
        val p = AdaptivePowerPolicy()
        p.start()
        p.screenChanged(true)
        p.shouldInfer(0L)
        p.shouldInfer(120_000L)
        p.screenChanged(false)
        p.screenChanged(true)
        assertTrue(p.shouldInfer(120_001L))
        assertTrue(p.shouldInfer(120_951L))
        p.observeHand(true, 121_000L)
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(122_000L))
    }
}
