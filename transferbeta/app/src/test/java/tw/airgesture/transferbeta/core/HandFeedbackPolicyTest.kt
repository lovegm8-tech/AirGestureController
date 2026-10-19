package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class HandFeedbackPolicyTest {
    @Test fun distinguishesHandFromRecognizedGesture() {
        val p = HandFeedbackPolicy()
        assertEquals(HandHint.HAND_SEEN, p.observe(true, "", 1000))
        assertEquals(HandHint.OPEN_PALM, p.observe(true, "Open_Palm", 1120))
        assertEquals(HandHint.CLOSED_FIST, p.observe(true, "Closed_Fist", 1240))
    }
    @Test fun displayDifferentiatesBackOfHandFromSwipeReadyPalm() {
        val p = HandFeedbackPolicy()
        assertEquals(HandHint.OPEN_PALM_NOT_READY,
            p.observe(true, "Open_Palm", 1000L, palmFacing = false))
        assertEquals(HandHint.OPEN_PALM,
            p.observe(true, "Open_Palm", 1120L, palmFacing = true))
        assertEquals(HandHint.OPEN_PALM_NOT_READY,
            p.observe(true, "Open_Palm", 1240L, palmFacing = false))
        // No extra gesture-action decision is made by this visual status.
        assertNull(p.observe(true, "", 1340L, palmFacing = false))
    }

    @Test fun throttlesRepeatedResultsWithoutHidingExistingBadge() {
        val p = HandFeedbackPolicy()
        assertEquals(HandHint.OPEN_PALM, p.observe(true, "Open_Palm", 1000))
        assertNull(p.observe(true, "Open_Palm", 1100))
        assertNull(p.observe(true, "Open_Palm", 1600))
        assertEquals(HandHint.OPEN_PALM, p.observe(true, "Open_Palm", 1760))
    }
    @Test fun briefTrackingGapDoesNotBlink() {
        val p = HandFeedbackPolicy()
        p.observe(true, "Open_Palm", 1000)
        assertNull(p.observe(false, "", 1150))
        assertNull(p.observe(false, "", 1550))
        assertEquals(HandHint.NONE, p.observe(false, "", 1701))
        assertNull(p.observe(false, "", 2600))
    }
    @Test fun stopsWhenHandDisappearsAndCanDetectAgain() {
        val p = HandFeedbackPolicy()
        p.observe(true, "Closed_Fist", 1000)
        assertEquals(HandHint.NONE, p.observe(false, "", 2600))
        assertEquals(HandHint.OPEN_PALM, p.observe(true, "Open_Palm", 3100))
        p.reset()
        assertEquals(HandHint.HAND_SEEN, p.observe(true, "Other", 4200))
    }
    @Test fun brieflyUncertainClassificationDoesNotFlashHandDetected() {
        val p = HandFeedbackPolicy()
        assertEquals(HandHint.OPEN_PALM, p.observe(true, "Open_Palm", 1000))
        assertNull(p.observe(true, "", 1120))
        assertNull(p.observe(true, "Other", 1300))
        // Display-only grace must expire if pose recognition stays uncertain.
        assertEquals(HandHint.HAND_SEEN, p.observe(true, "", 1321))
        // New, reliable fist must immediately replace previous status.
        assertEquals(HandHint.CLOSED_FIST, p.observe(true, "Closed_Fist", 1350))
        assertNull(p.observe(true, "", 1500))
    }

    @Test fun firstDetectionRemainsImmediateBeforePoseClassification() {
        val p = HandFeedbackPolicy()
        assertEquals(HandHint.HAND_SEEN, p.observe(true, "", 1000))
        assertEquals(HandHint.CLOSED_FIST, p.observe(true, "Closed_Fist", 1115))
        assertNull(p.observe(true, "", 1200))
        assertEquals(HandHint.HAND_SEEN, p.observe(true, "", 1440))
    }

    @Test fun noHandAtStartupProducesNoSpam() {
        val p = HandFeedbackPolicy()
        assertNull(p.observe(false, "", 1000))
        assertNull(p.observe(false, "", 2500))
    }
}