package tw.airgesture.transferbeta.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleMotionDetectorTest {
    private fun still() = IntArray(16 * 12) { 100 }

    @Test fun partialHandEnteringBottomTriggersEarlyVerification() {
        val p = IdleMotionDetector()
        assertFalse(p.observe(still(), 1000L))
        val entering = still()
        for (i in 160 until 166) entering[i] += 42
        assertTrue(p.observe(entering, 1180L))
    }

    @Test fun lightingChangeIsNotMistakenForHandMovement() {
        val p = IdleMotionDetector()
        assertFalse(p.observe(still(), 1000L))
        assertFalse(p.observe(IntArray(192) { 140 }, 1180L))
    }

    @Test fun fewChangedCellsAtTopDoNotWakeIdleRecognizer() {
        val p = IdleMotionDetector()
        assertFalse(p.observe(still(), 1000L))
        val noisyTop = still()
        for (i in 0 until 8) noisyTop[i] += 42
        assertFalse(p.observe(noisyTop, 1180L))
    }

    @Test fun sizeableMotionAnywhereStillWakesRecognizer() {
        val p = IdleMotionDetector()
        assertFalse(p.observe(still(), 1000L))
        val moving = still()
        for (i in 0 until 28) moving[i] += 42
        assertTrue(p.observe(moving, 1180L))
    }

    @Test fun boundedTriggerCooldownAvoidsRepeatedWakeups() {
        val p = IdleMotionDetector()
        val a = still()
        val b = still().also { for (i in 160 until 168) it[i] += 42 }
        assertFalse(p.observe(a, 1000L))
        assertTrue(p.observe(b, 1180L))
        assertFalse(p.observe(a, 1400L))
        assertFalse(p.observe(b, 1500L))
        assertTrue(p.observe(a, 2800L))
    }
}
