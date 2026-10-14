package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

/** Regression tests for the opt-in fast-palm mode. NOT executed yet. */
class FastSwipeAssistTest {
    private val open = GestureDecisionEngine.Shape.OPEN_PALM
    private val fist = GestureDecisionEngine.Shape.CLOSED_FIST
    private val none = GestureDecisionEngine.Shape.NO_HAND
    private val other = GestureDecisionEngine.Shape.OTHER

    private fun engine(): GestureDecisionEngine =
        GestureDecisionEngine(GestureDecisionEngine.Options(fastSwipeAssistEnabled = true))

    @Test fun quickDownSwipeCanUseFirstAndSecondOpenFrame() {
        val e = engine()
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .22f), 0))
        assertEquals(GestureDecisionEngine.Action.SWIPE_DOWN,
            e.accept(GestureDecisionEngine.Frame(open, .47f), 130))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .65f), 220))
    }

    @Test fun quickUpSwipeWorksWithTwoFrames() {
        val e = engine()
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .75f), 0))
        assertEquals(GestureDecisionEngine.Action.SWIPE_UP,
            e.accept(GestureDecisionEngine.Frame(open, .51f), 130))
    }

    @Test fun transientBlurryFrameDoesNotEraseValidStart() {
        val e = engine()
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .26f), 0))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .26f), 100))
        assertNull(e.accept(GestureDecisionEngine.Frame(none, confidence = .1f), 180))
        assertEquals(GestureDecisionEngine.Action.SWIPE_DOWN,
            e.accept(GestureDecisionEngine.Frame(open, .53f), 260))
    }

    @Test fun neverFiresDuringBlurWithoutValidEndPosition() {
        val e = engine()
        e.accept(GestureDecisionEngine.Frame(open, .20f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .20f), 90)
        for (time in listOf(180L, 250L, 400L)) {
            assertNull(e.accept(GestureDecisionEngine.Frame(none), time))
        }
    }

    @Test fun prolongedLossRequiresFreshPalmOrigin() {
        val e = engine()
        e.accept(GestureDecisionEngine.Frame(open, .24f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .24f), 100)
        e.accept(GestureDecisionEngine.Frame(none), 180)
        e.accept(GestureDecisionEngine.Frame(none), 460)
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .75f), 600))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .75f), 730))
    }

    @Test fun confidentOtherGestureBreaksMotionTrail() {
        val e = engine()
        e.accept(GestureDecisionEngine.Frame(open, .20f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .20f), 90)
        assertNull(e.accept(GestureDecisionEngine.Frame(other, confidence = .92f), 170))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .55f), 260))
    }

    @Test fun onePalmFrameDoesNotArmFistClick() {
        val e = engine()
        e.accept(GestureDecisionEngine.Frame(open, .55f), 0)
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 100))
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 200))
    }

    @Test fun twoPalmFramesStillAllowNormalFistClick() {
        val e = engine()
        e.accept(GestureDecisionEngine.Frame(open, .55f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .55f), 100)
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 180))
        assertEquals(GestureDecisionEngine.Action.TAP_CENTER,
            e.accept(GestureDecisionEngine.Frame(fist), 280))
    }

    @Test fun fastRightSwipeStillRequiresStrongHorizontalDominance() {
        val e = engine()
        e.accept(GestureDecisionEngine.Frame(open, .50f, palmX = .20f), 0)
        assertEquals(GestureDecisionEngine.Action.SWIPE_BACK,
            e.accept(GestureDecisionEngine.Frame(open, .52f, palmX = .47f), 130))
        val diagonal = engine()
        diagonal.accept(GestureDecisionEngine.Frame(open, .20f, palmX = .20f), 0)
        assertNull(diagonal.accept(GestureDecisionEngine.Frame(open, .41f, palmX = .40f), 130))
    }

    @Test fun legacyDefaultStillRequiresThirdPalmFrame() {
        val e = GestureDecisionEngine()
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .20f), 0))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .49f), 130))
    }

    @Test fun tooEarlyMotionIsNotAnAccidentalSwipe() {
        val e = engine()
        e.accept(GestureDecisionEngine.Frame(open, .20f), 0)
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .55f), 50))
        assertEquals(GestureDecisionEngine.Action.SWIPE_DOWN,
            e.accept(GestureDecisionEngine.Frame(open, .58f), 130))
    }

    @Test fun expiredObservationWithoutNoneFramesMustNotBridge() {
        val e = engine()
        e.accept(GestureDecisionEngine.Frame(open, .20f), 0)
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .55f), 450))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .55f), 570))
    }
}
