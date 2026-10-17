package tw.airgesture.transferbeta

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureSequenceTest {
    @Test fun closedFistAfterTwoSecondsSendsWithoutOpenPalmFirst() {
        val g = GestureSequence()
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 1000L))
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 2999L))
        assertEquals(GestureSequence.Result.SEND, g.accept("Closed_Fist", 3000L))
    }

    @Test fun incomingOfferMakesTheSameFistGestureReceive() {
        val g = GestureSequence()
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 1000L, true))
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 2999L, true))
        assertEquals(GestureSequence.Result.RECEIVE, g.accept("Closed_Fist", 3000L, true))
    }

    @Test fun shortFistDoesNotTriggerEitherAction() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000L)
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 2500L))
        g.accept("Open_Palm", 2700L)
        g.accept("Open_Palm", 3000L)
        assertEquals(GestureSequence.Result.NONE, g.accept("Open_Palm", 3200L))
    }

    @Test fun oneLongFistCannotTriggerTwice() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000L)
        assertEquals(GestureSequence.Result.SEND, g.accept("Closed_Fist", 3000L))
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 6000L))
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 9000L, true))
    }

    @Test fun releaseThenNewHoldCanTriggerAgain() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000L)
        assertEquals(GestureSequence.Result.SEND, g.accept("Closed_Fist", 3000L))
        g.accept("Open_Palm", 3100L)
        g.accept("Open_Palm", 3400L)
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 3500L, true))
        assertEquals(GestureSequence.Result.RECEIVE, g.accept("Closed_Fist", 5500L, true))
    }

    @Test fun aSingleNoisyPalmFrameDoesNotRearm() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000L)
        assertEquals(GestureSequence.Result.SEND, g.accept("Closed_Fist", 3000L))
        g.accept("Open_Palm", 3100L)
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 3200L))
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 5600L))
    }

    @Test fun shortUnknownGapKeepsTheFistTimer() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000L)
        g.accept("Closed_Fist", 1500L)
        g.accept("", 1600L)
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 1800L))
        assertEquals(GestureSequence.Result.SEND, g.accept("Closed_Fist", 3000L))
    }

    @Test fun longUnknownGapRequiresANewTwoSecondHold() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000L)
        g.accept("", 1600L)
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 2000L))
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 3999L))
        assertEquals(GestureSequence.Result.SEND, g.accept("Closed_Fist", 4000L))
    }

    @Test fun isolatedPalmNeverTriggers() {
        val g = GestureSequence()
        assertEquals(GestureSequence.Result.NONE, g.accept("Open_Palm", 1000L))
        assertEquals(GestureSequence.Result.NONE, g.accept("Open_Palm", 5000L, true))
    }

    @Test fun resetAtIncomingOfferStartsNewHold() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000L)
        g.reset()
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 3000L, true))
        assertEquals(GestureSequence.Result.RECEIVE, g.accept("Closed_Fist", 5000L, true))
    }
}
