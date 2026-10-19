package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class HandDisplayGateTest {
    private fun hand(
        x: Float = .50f,
        y: Float = .60f
    ): FloatArray = FloatArray(42) { n ->
        if (n % 2 == 0) x else y
    }.apply {
        // A plausible on-screen hand's wrist and knuckles.
        this[0] = x
        this[1] = y + .19f
        this[10] = x - .06f  // landmark 5 (index MCP)
        this[11] = y - .03f
        this[18] = x
        this[19] = y - .07f // landmark 9 (middle MCP)
        this[34] = x + .06f // landmark 17 (pinky MCP)
        this[35] = y - .03f
    }

    @Test fun oneFrameGhostNeverShowsHandBadgeOrPalmTrail() {
        val gate = HandDisplayGate()
        assertFalse(gate.observe(hand(), .5f, .6f, 1000L))
        assertTrue(gate.observe(hand(), .51f, .59f, 1120L))
    }

    @Test fun distantJumpOrLongGapRequiresReconfirmation() {
        val gate = HandDisplayGate()
        assertFalse(gate.observe(hand(), .5f, .6f, 1000L))
        assertFalse(gate.observe(hand(.9f, .3f), .9f, .3f, 1150L))
        assertFalse(gate.observe(hand(.9f, .3f), .9f, .3f, 1800L))
        assertTrue(gate.observe(hand(.9f, .3f), .9f, .3f, 1900L))
    }

    @Test fun absentOrDegenerateLandmarksClearVisualConfidence() {
        val gate = HandDisplayGate()
        assertFalse(gate.observe(null, .5f, .6f, 1000L))
        assertFalse(gate.observe(FloatArray(42) { .5f }, .5f, .6f, 1100L))
        assertFalse(gate.observe(hand(), .5f, .6f, 1200L))
        assertTrue(gate.observe(hand(), .5f, .6f, 1330L))
        assertFalse(gate.observe(null, null, null, 1440L))
        assertFalse(gate.observe(hand(), .5f, .6f, 1550L))
    }

    @Test fun palmNearBottomOfFrameRemainsEligibleForVisualMarker() {
        val gate = HandDisplayGate()
        val lower = hand(y = .78f)
        assertFalse(gate.observe(lower, .5f, .91f, 1000L))
        assertTrue(gate.observe(lower, .5f, .89f, 1150L))
    }

    @Test fun noCameraOrExtraInferenceNeeded() {
        val gate = HandDisplayGate()
        assertFalse(gate.observe(hand(), .5f, .6f, 1000L))
        gate.reset()
        assertFalse(gate.observe(hand(), .5f, .6f, 1150L))
    }
}
