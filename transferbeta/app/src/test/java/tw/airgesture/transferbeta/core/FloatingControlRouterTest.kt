package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class FloatingControlRouterTest {
    private fun step(
        r: FloatingControlRouter,
        pose: String, time: Long,
        offer: Boolean = false, youtube: Boolean = false,
        px: Float? = .5f, py: Float? = .5f,
        ix: Float? = null, iy: Float? = null
    ) = r.accept(pose, .90f, px, py, ix, iy, time, offer, youtube)

    @Test fun directFistHeldOnePointFiveSecondsTapsExactlyOnce() {
        val r = FloatingControlRouter()
        val outputs = (0..36).map {
            step(r, "Closed_Fist", 1000L + 100L * it).command
        }
        assertTrue(outputs.take(15).all { it == null })
        assertEquals(FloatingControlRouter.Command.TAP, outputs[15])
        assertEquals(1, outputs.count { it == FloatingControlRouter.Command.TAP })
        assertFalse(outputs.contains(FloatingControlRouter.Command.SEND))
    }

    @Test fun directFistMustNotTapBeforeExactlyFifteenHundredMillis() {
        val r = FloatingControlRouter()
        for (i in 0..14) {
            assertNull(step(r, "Closed_Fist", 1000L + i * 100L).command)
        }
        assertNull(step(r, "Closed_Fist", 2499L).command)
        assertEquals(FloatingControlRouter.Command.TAP,
            step(r, "Closed_Fist", 2500L).command)
        assertNull(step(r, "Closed_Fist", 2600L).command)
    }

    @Test fun directFistInYoutubeSendsMediaCommandAndNotTap() {
        val r = FloatingControlRouter()
        val outputs = (0..35).map {
            step(r, "Closed_Fist", 1000L + it * 100L, youtube = true).command
        }
        assertTrue(outputs.take(15).all { it == null })
        assertEquals(FloatingControlRouter.Command.MEDIA_PLAY_PAUSE, outputs[15])
        assertEquals(1, outputs.count {
            it == FloatingControlRouter.Command.MEDIA_PLAY_PAUSE
        })
        assertFalse(outputs.contains(FloatingControlRouter.Command.TAP))
    }

    @Test fun palmThenFistGrabsImmediatelyAndBlocksLaterLongHoldTap() {
        val r = FloatingControlRouter()
        step(r, "Open_Palm", 1000L)
        step(r, "Open_Palm", 1100L)
        assertNull(step(r, "Closed_Fist", 1200L).command)
        assertEquals(FloatingControlRouter.Command.SEND,
            step(r, "Closed_Fist", 1300L).command)
        val tail = (14..48).map {
            step(r, "Closed_Fist", 1000L + it * 100L).command
        }
        assertTrue(tail.all { it == null })
        assertNull(step(r, "Open_Palm", 5000L).command)
        assertNull(step(r, "Open_Palm", 5100L).command)
    }

    @Test fun shortOrNoisePalmDoesNotAccidentallyGrab() {
        val r = FloatingControlRouter()
        step(r, "Open_Palm", 900L)
        assertNull(step(r, "Closed_Fist", 1000L).command)
        val outputs = (1..25).map {
            step(r, "Closed_Fist", 1000L + 100L * it).command
        }
        assertEquals(1, outputs.count { it == FloatingControlRouter.Command.TAP })
        assertFalse(outputs.contains(FloatingControlRouter.Command.SEND))
    }

    @Test fun fistThenPalmReceivesWhenOfferIsWaiting() {
        val r = FloatingControlRouter()
        val events = (0..26).map {
            step(r, "Closed_Fist", 1000L + 100L * it, offer = true).command
        }
        assertTrue(events.all { it == null })
        assertNull(step(r, "Open_Palm", 3700L, offer = true).command)
        assertEquals(FloatingControlRouter.Command.RECEIVE,
            step(r, "Open_Palm", 3800L, offer = true).command)
        assertNull(step(r, "Open_Palm", 3900L, offer = true).command)
    }

    @Test fun shortFistThenPalmAlsoReceivesOnlyWhenOfferExists() {
        val r = FloatingControlRouter()
        step(r, "Closed_Fist", 1000L, offer = true)
        step(r, "Closed_Fist", 1100L, offer = true)
        step(r, "Open_Palm", 1200L, offer = true)
        assertEquals(FloatingControlRouter.Command.RECEIVE,
            step(r, "Open_Palm", 1300L, offer = true).command)
        val other = FloatingControlRouter()
        step(other, "Closed_Fist", 1000L)
        step(other, "Closed_Fist", 1100L)
        step(other, "Open_Palm", 1200L)
        assertNull(step(other, "Open_Palm", 1300L).command)
    }

    @Test fun pendingReceiveSuppressesPalmToFistGrabAndAllClicks() {
        val r = FloatingControlRouter()
        step(r, "Open_Palm", 1000L, offer = true)
        step(r, "Open_Palm", 1100L, offer = true)
        val events = (0..26).map {
            step(r, "Closed_Fist", 1200L + it * 100L, offer = true).command
        }
        assertTrue(events.all { it == null })
        step(r, "Open_Palm", 3900L, offer = true)
        assertEquals(FloatingControlRouter.Command.RECEIVE,
            step(r, "Open_Palm", 4000L, offer = true).command)
    }

    @Test fun onePalmFrameCannotRearmAfterConsumedGrab() {
        val r = FloatingControlRouter()
        step(r, "Open_Palm", 1000L)
        step(r, "Open_Palm", 1100L)
        step(r, "Closed_Fist", 1200L)
        assertEquals(FloatingControlRouter.Command.SEND,
            step(r, "Closed_Fist", 1300L).command)
        step(r, "Open_Palm", 1400L)
        val results = (0..22).map {
            step(r, "Closed_Fist", 1500L + it * 100L).command
        }
        assertTrue(results.all { it == null })
    }

    @Test fun openPalmSwipeUpRoutesWithoutTriggeringFileGrab() {
        val r = FloatingControlRouter()
        val events = listOf(
            step(r, "Open_Palm", 1000L, py = .82f),
            step(r, "Open_Palm", 1100L, py = .82f),
            step(r, "Open_Palm", 1250L, py = .50f)
        )
        assertEquals(1, events.count { it.command == FloatingControlRouter.Command.SWIPE_UP })
    }

    @Test fun openPalmLeftSwipeMovesFromRightEdgeWithoutGrabbing() {
        val router = FloatingControlRouter()
        val events = listOf(
            step(router, "Open_Palm", 1000L, px = .88f, py = .5f),
            step(router, "Open_Palm", 1100L, px = .83f, py = .5f),
            step(router, "Open_Palm", 1250L, px = .38f, py = .5f),
            step(router, "Open_Palm", 1350L, px = .18f, py = .5f)
        )
        assertEquals(1, events.count {
            it.command == FloatingControlRouter.Command.SWIPE_LEFT
        })
        assertFalse(events.any {
            it.command == FloatingControlRouter.Command.SEND ||
                it.command == FloatingControlRouter.Command.RECEIVE
        })
        assertNull(step(router, "Closed_Fist", 1600L).command)
    }

    @Test fun palmVerticalMovementDoesNotTriggerLeftSwipe() {
        val router = FloatingControlRouter()
        val events = listOf(
            step(router, "Open_Palm", 1000L, px = .80f, py = .90f),
            step(router, "Open_Palm", 1100L, px = .78f, py = .80f),
            step(router, "Open_Palm", 1250L, px = .63f, py = .31f)
        )
        assertTrue(events.none {
            it.command == FloatingControlRouter.Command.SWIPE_LEFT
        })
    }

    @Test fun openPalmRightSwipeRoutesBack() {
        val r = FloatingControlRouter()
        val events = listOf(
            step(r, "Open_Palm", 1000L, px = .15f, py = .50f),
            step(r, "Open_Palm", 1100L, px = .15f, py = .50f),
            step(r, "Open_Palm", 1250L, px = .58f, py = .50f)
        )
        assertEquals(1, events.count { it.command == FloatingControlRouter.Command.SWIPE_BACK })
    }

    @Test fun indexFingerDwellClickRequiresTwoSeconds() {
        val r = FloatingControlRouter()
        assertFalse(step(r, "Pointing_Up", 1000L, ix = .4f, iy = .5f).pointer?.click ?: true)
        assertFalse(step(r, "Pointing_Up", 2999L, ix = .4f, iy = .5f).pointer?.click ?: true)
        assertTrue(step(r, "Pointing_Up", 3000L, ix = .4f, iy = .5f).pointer?.click == true)
        assertFalse(step(r, "Pointing_Up", 3200L, ix = .4f, iy = .5f).pointer?.click ?: true)
    }

    @Test fun classifiedHandAt60PercentCanTriggerOnePointFiveSecondTap() {
        val r = FloatingControlRouter()
        val actions = (0..25).map {
            r.accept("Closed_Fist", .60f, null, null, null, null,
                1000L + it * 100L, false, false).command
        }
        assertEquals(1, actions.count { it == FloatingControlRouter.Command.TAP })
    }

    @Test fun weakRecognitionDoesNotTriggerActions() {
        val r = FloatingControlRouter()
        val events = (0..34).map {
            r.accept("Closed_Fist", .40f, null, null, null, null,
                1000L + it * 100L, false, false)
        }
        assertTrue(events.all { it.command == null })
    }
}
