package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

/** Isolated from Android, camera hardware and the legacy click engine. */
class YouTubeGestureRouterTest {
    private val palm = GestureDecisionEngine.Shape.OPEN_PALM
    private val fist = GestureDecisionEngine.Shape.CLOSED_FIST
    private val none = GestureDecisionEngine.Shape.NO_HAND
    private val other = GestureDecisionEngine.Shape.OTHER
    private val tap = GestureDecisionEngine.Action.TAP_CENTER
    private val media = GestureDecisionEngine.Action.MEDIA_PLAY_PAUSE

    private fun arm(router: YouTubeGestureRouter, start: Long = 100) {
        assertNull(router.route(palm, start, null, true))
        assertNull(router.route(palm, start + 110, null, true))
    }

    @Test fun `two fist frames after confirmed palm immediately toggle YouTube`() {
        val r = YouTubeGestureRouter()
        arm(r)
        assertNull(r.route(fist, 320, null, true))
        // action can be null even when circular-volume mode suppressed original tap
        assertEquals(media, r.route(fist, 420, null, true))
        assertNull(r.route(fist, 520, tap, true))
        assertNull(r.route(fist, 1020, tap, true))
        assertNull(r.route(fist, 1420, null, true))
    }

    @Test fun `never send center tap to YouTube on short or interrupted fist`() {
        val r = YouTubeGestureRouter()
        arm(r)
        assertNull(r.route(fist, 310, tap, true))
        assertNull(r.route(none, 430, tap, true))
        assertEquals(media, r.route(fist, 530, tap, true))
    }

    @Test fun `flickering hand tracking can bridge a short gap`() {
        val r = YouTubeGestureRouter()
        arm(r)
        assertNull(r.route(fist, 310, null, true))
        assertNull(r.route(none, 480, null, true))
        assertEquals(media, r.route(fist, 620, null, true))
    }

    @Test fun `a fist alone cannot toggle without confirmed palm`() {
        val r = YouTubeGestureRouter()
        assertNull(r.route(fist, 100, tap, true))
        assertNull(r.route(fist, 220, tap, true))
        assertNull(r.route(fist, 390, null, true))
    }

    @Test fun `old palm observation expires and cannot trigger stale fist`() {
        val r = YouTubeGestureRouter()
        arm(r)
        assertNull(r.route(none, 800, null, true))
        assertNull(r.route(fist, 1620, tap, true))
        assertNull(r.route(fist, 1700, tap, true))
    }

    @Test fun `reopen palm for 320ms and cooldown then allow one more media key`() {
        val r = YouTubeGestureRouter()
        arm(r)
        assertNull(r.route(fist, 320, null, true))
        assertEquals(media, r.route(fist, 420, null, true))
        assertNull(r.route(palm, 560, null, true))
        assertNull(r.route(palm, 760, null, true))
        assertNull(r.route(palm, 940, null, true))
        assertNull(r.route(fist, 1040, null, true))
        assertEquals(media, r.route(fist, 1140, null, true))
    }

    @Test fun `brief palm model flicker does not rearm a held fist`() {
        val r = YouTubeGestureRouter()
        arm(r)
        assertNull(r.route(fist, 320, null, true))
        assertEquals(media, r.route(fist, 420, null, true))
        assertNull(r.route(palm, 550, null, true))
        assertNull(r.route(fist, 660, null, true))
        assertNull(r.route(fist, 760, tap, true))
        assertNull(r.route(fist, 980, tap, true))
    }

    @Test fun `switching to other apps passes old tap and clears pending YouTube`() {
        val r = YouTubeGestureRouter()
        arm(r)
        assertEquals(tap, r.route(fist, 320, tap, false))
        assertEquals(tap, r.route(fist, 1040, tap, false))
        // Return to YouTube while holding fist: not armed.
        assertNull(r.route(fist, 1100, tap, true))
        assertNull(r.route(fist, 1250, null, true))
    }

    @Test fun `swipes remain available in YouTube outside circle candidates`() {
        val r = YouTubeGestureRouter()
        assertEquals(GestureDecisionEngine.Action.SWIPE_UP,
            r.route(palm, 100, GestureDecisionEngine.Action.SWIPE_UP, true))
    }
}
