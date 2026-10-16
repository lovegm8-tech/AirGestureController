package tw.airgesture.controller.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HandVisualClassifierTest {
    private val open = GestureDecisionEngine.Shape.OPEN_PALM
    private val fist = GestureDecisionEngine.Shape.CLOSED_FIST
    private val other = GestureDecisionEngine.Shape.OTHER
    private val none = GestureDecisionEngine.Shape.NO_HAND

    @Test fun detectedHandWithoutCertainGestureShowsGeneralHandStatus() {
        assertEquals(HandVisualState.HAND_DETECTED,
            HandVisualClassifier.classify(other, .9f, true, false))
        assertEquals(HandVisualState.HAND_DETECTED,
            HandVisualClassifier.classify(fist, .3f, true, false))
    }

    @Test fun palmAndFistRequireOriginalConfidenceAndDoNotAffectActions() {
        assertEquals(HandVisualState.OPEN_PALM,
            HandVisualClassifier.classify(open, .65f, true, false))
        assertEquals(HandVisualState.CLOSED_FIST,
            HandVisualClassifier.classify(fist, .70f, true, false))
    }

    @Test fun pointingHasItsOwnState() {
        assertEquals(HandVisualState.INDEX_FINGER,
            HandVisualClassifier.classify(other, .9f, true, true))
    }

    @Test fun noHandCollapsesOverlayToOriginalTinyDot() {
        assertEquals(HandVisualState.NO_HAND,
            HandVisualClassifier.classify(none, 0f, false, false))
        assertEquals(HandVisualState.NO_HAND,
            HandVisualClassifier.classify(other, .9f, false, false))
    }
}
