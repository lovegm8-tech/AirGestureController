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
        assertFalse(p.shouldInfer(61_150L))
        assertTrue(p.shouldInfer(61_165L))
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(63_200L))
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(63_201L))
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
