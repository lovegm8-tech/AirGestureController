package tw.airgesture.transferbeta

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureSequenceTest {
    // Simulates fast camera observations while a fist is continuously in frame.
    private fun heldFist(
        detector: GestureSequence,
        startMs: Long,
        endMs: Long,
        receiveAvailable: Boolean = false
    ): List<GestureSequence.Result> {
        val events = mutableListOf<GestureSequence.Result>()
        var t = startMs
        while (t <= endMs) {
            events += detector.accept("Closed_Fist", t, receiveAvailable)
            t += 100L
        }
        return events
    }

    @Test fun closedFistAfterTwoSecondsSendsWithoutOpenPalmFirst() {
        val events = heldFist(GestureSequence(), 1000L, 3000L)
        assertEquals(20, events.count { it == GestureSequence.Result.NONE })
        assertEquals(GestureSequence.Result.SEND, events.last())
    }

    @Test fun incomingOfferMakesTheSameFistGestureReceive() {
        val events = heldFist(GestureSequence(), 1000L, 3000L, true)
        assertEquals(20, events.count { it == GestureSequence.Result.NONE })
        assertEquals(GestureSequence.Result.RECEIVE, events.last())
    }

    @Test fun shortFistDoesNotTriggerEitherAction() {
        val g = GestureSequence()
        val events = heldFist(g, 1000L, 2500L)
        assertEquals(true, events.all { it == GestureSequence.Result.NONE })
        g.accept("Open_Palm", 2700L)
        g.accept("Open_Palm", 3000L)
        assertEquals(GestureSequence.Result.NONE, g.accept("Open_Palm", 3200L))
    }

    @Test fun oneLongFistCannotTriggerTwice() {
        val g = GestureSequence()
        val events = heldFist(g, 1000L, 6000L)
        assertEquals(1, events.count { it == GestureSequence.Result.SEND })
        assertEquals(0, events.count { it == GestureSequence.Result.RECEIVE })
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 6100L, true))
    }

    @Test fun releaseThenNewHoldCanTriggerAgain() {
        val g = GestureSequence()
        assertEquals(GestureSequence.Result.SEND, heldFist(g, 1000L, 3000L).last())
        g.accept("Open_Palm", 3100L)
        g.accept("Open_Palm", 3400L)
        val receiveEvents = heldFist(g, 3500L, 5500L, true)
        assertEquals(1, receiveEvents.count { it == GestureSequence.Result.RECEIVE })
        assertEquals(GestureSequence.Result.RECEIVE, receiveEvents.last())
    }

    @Test fun aSingleNoisyPalmFrameDoesNotRearm() {
        val g = GestureSequence()
        assertEquals(GestureSequence.Result.SEND, heldFist(g, 1000L, 3000L).last())
        g.accept("Open_Palm", 3100L)
        val next = heldFist(g, 3200L, 5600L)
        assertEquals(true, next.all { it == GestureSequence.Result.NONE })
    }

    @Test fun shortUnknownGapKeepsTheFistTimer() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000L)
        g.accept("Closed_Fist", 1500L)
        g.accept("", 1600L)
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 1800L))
        g.accept("Closed_Fist", 2100L)
        g.accept("Closed_Fist", 2400L)
        g.accept("Closed_Fist", 2700L)
        assertEquals(GestureSequence.Result.SEND, g.accept("Closed_Fist", 3000L))
    }

    @Test fun longUnknownGapRequiresANewTwoSecondHold() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000L)
        g.accept("", 1600L)
        val restarted = heldFist(g, 2000L, 4000L)
        assertEquals(20, restarted.count { it == GestureSequence.Result.NONE })
        assertEquals(GestureSequence.Result.SEND, restarted.last())
    }

    @Test fun firedFistRemainsConsumedAcrossLongInferenceGap() {
        val g = GestureSequence()
        assertEquals(GestureSequence.Result.SEND, heldFist(g, 1000L, 3000L).last())
        g.accept("", 4700L)
        assertEquals(true, heldFist(g, 5100L, 8200L).all {
            it == GestureSequence.Result.NONE
        })
        g.accept("Open_Palm", 8400L)
        g.accept("Open_Palm", 8700L)
        assertEquals(GestureSequence.Result.RECEIVE,
            heldFist(g, 9000L, 11000L, true).last())
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
        val events = heldFist(g, 3000L, 5000L, true)
        assertEquals(20, events.count { it == GestureSequence.Result.NONE })
        assertEquals(GestureSequence.Result.RECEIVE, events.last())
    }
}
