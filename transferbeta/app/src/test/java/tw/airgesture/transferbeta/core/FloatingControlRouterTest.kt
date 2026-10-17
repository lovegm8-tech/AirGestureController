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

    @Test fun fistHeldTwoSecondsSendsWithNoPrecedingPalm() {
        val r = FloatingControlRouter()
        var sendCount = 0
        for (i in 0..35) {
            if (step(r, "Closed_Fist", 1000L + i * 100L).command ==
                FloatingControlRouter.Command.SEND) sendCount++
        }
        assertEquals(1, sendCount)
    }

    @Test fun offerChangesLongFistFromSendToReceive() {
        val r = FloatingControlRouter()
        val results = (0..25).map { step(r, "Closed_Fist", 1000L + it * 100L, offer = true) }
        assertEquals(1, results.count { it.command == FloatingControlRouter.Command.RECEIVE })
        assertEquals(0, results.count { it.command == FloatingControlRouter.Command.SEND })
    }

    @Test fun shortFistCannotTapUntilPalmRelease() {
        val r = FloatingControlRouter()
        step(r, "Open_Palm", 1000L)
        step(r, "Open_Palm", 1100L)
        assertNull(step(r, "Closed_Fist", 1200L).command)
        assertNull(step(r, "Closed_Fist", 1300L).command)
        assertNull(step(r, "Closed_Fist", 1400L).command)
        assertNull(step(r, "Open_Palm", 1500L).command)
        assertEquals(FloatingControlRouter.Command.TAP,
            step(r, "Open_Palm", 1600L).command)
        assertNull(step(r, "Open_Palm", 1700L).command)
    }

    @Test fun youtubeShortFistUsesMediaKeyNotTouch() {
        val r = FloatingControlRouter()
        step(r, "Open_Palm", 1000L)
        step(r, "Open_Palm", 1100L)
        step(r, "Closed_Fist", 1200L, youtube = true)
        step(r, "Closed_Fist", 1300L, youtube = true)
        step(r, "Open_Palm", 1400L, youtube = true)
        assertEquals(FloatingControlRouter.Command.MEDIA_PLAY_PAUSE,
            step(r, "Open_Palm", 1500L, youtube = true).command)
    }

    @Test fun longFistNeverCausesEarlyOrReleasedTap() {
        val r = FloatingControlRouter()
        step(r, "Open_Palm", 1000L)
        step(r, "Open_Palm", 1100L)
        val outputs = (0..27).map {
            step(r, "Closed_Fist", 1200L + it * 100L).command
        } + listOf(
            step(r, "Open_Palm", 4000L).command,
            step(r, "Open_Palm", 4100L).command
        )
        assertEquals(1, outputs.count { it == FloatingControlRouter.Command.SEND })
        assertEquals(0, outputs.count { it == FloatingControlRouter.Command.TAP })
        assertEquals(0, outputs.count { it == FloatingControlRouter.Command.MEDIA_PLAY_PAUSE })
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

    @Test fun classifiedHandAt60PercentCanStillTriggerTwoSecondGrab() {
        val r = FloatingControlRouter()
        var count = 0
        for (i in 0..30) {
            val decision = r.accept("Closed_Fist", .60f, null, null, null, null,
                1000L + i * 100L, false, false)
            if (decision.command == FloatingControlRouter.Command.SEND) count++
        }
        assertEquals(1, count)
    }

    @Test fun openPalmSwipeAt60PercentMatchesHandStatus() {
        val r = FloatingControlRouter()
        val first = r.accept("Open_Palm", .60f, .50f, .85f, null, null,
            1000L, false, false)
        val second = r.accept("Open_Palm", .60f, .50f, .85f, null, null,
            1100L, false, false)
        val third = r.accept("Open_Palm", .60f, .50f, .50f, null, null,
            1250L, false, false)
        assertNull(first.command)
        assertNull(second.command)
        assertEquals(FloatingControlRouter.Command.SWIPE_UP, third.command)
    }

    @Test fun noGestureOnWeakRecognition() {
        val r = FloatingControlRouter()
        val results = (0..30).map {
            r.accept("Closed_Fist", .40f, null, null, null, null,
                1000L + 100 * it, false, false)
        }
        assertTrue(results.all { it.command == null })
    }
}
