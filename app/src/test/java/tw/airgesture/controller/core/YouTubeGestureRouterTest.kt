package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class YouTubeGestureRouterTest {
    private val palm = GestureDecisionEngine.Shape.OPEN_PALM
    private val fist = GestureDecisionEngine.Shape.CLOSED_FIST
    private val none = GestureDecisionEngine.Shape.NO_HAND
    private val other = GestureDecisionEngine.Shape.OTHER
    private val tap = GestureDecisionEngine.Action.TAP_CENTER
    private val media = GestureDecisionEngine.Action.MEDIA_PLAY_PAUSE

    private fun startYouTubeFist(
        router: YouTubeGestureRouter,
        start: Long = 200L
    ) {
        assertNull(router.route(palm, start - 150L, null, true))
        // The original engine has just confirmed palm -> fist; withhold tap.
        assertNull(router.route(fist, start, tap, true))
    }

    @Test fun `YouTube long fist never taps video before media toggle`() {
        for (duration in listOf(500L, 700L, 1000L)) {
            val router = YouTubeGestureRouter(longHoldMs = duration)
            startYouTubeFist(router)
            var time = 400L
            while (time < 200L + duration) {
                assertNull("time=$time duration=$duration",
                    router.route(fist, time, null, true))
                time += 200L
            }
            assertEquals("duration=$duration", media,
                router.route(fist, 200L + duration, tap, true))
            assertNull(router.route(fist, 200L + duration + 150L, null, true))
            assertNull(router.route(fist, 200L + duration + 300L, tap, true))
        }
    }

    @Test fun `short YouTube fist taps once after confirmed release`() {
        val router = YouTubeGestureRouter()
        startYouTubeFist(router)
        assertNull(router.route(fist, 340L, null, true))
        assertNull(router.route(palm, 430L, null, true))
        assertEquals(tap, router.route(palm, 580L, null, true))
        assertNull(router.route(palm, 820L, null, true))
        assertNull(router.route(fist, 1500L, null, true))
    }

    @Test fun `YouTube short fist can release by missing hand without hanging`() {
        val router = YouTubeGestureRouter()
        startYouTubeFist(router)
        assertNull(router.route(none, 310L, null, true))
        assertNull(router.route(none, 520L, null, true))
        assertEquals(tap, router.route(none, 800L, null, true))
        assertNull(router.route(none, 1000L, null, true))
    }

    @Test fun `YouTube fist tracking gap does not cause delayed media toggle`() {
        val router = YouTubeGestureRouter()
        startYouTubeFist(router)
        assertNull(router.route(fist, 350L, null, true))
        assertNull(router.route(fist, 990L, tap, true))
        assertNull(router.route(fist, 1150L, null, true))
    }

    @Test fun `other apps keep original first and second click`() {
        val router = YouTubeGestureRouter()
        assertEquals(tap, router.route(fist, 200L, tap, false))
        assertEquals(tap, router.route(fist, 900L, tap, false))
        assertEquals(GestureDecisionEngine.Action.SWIPE_DOWN,
            router.route(palm, 1200L, GestureDecisionEngine.Action.SWIPE_DOWN, false))
    }

    @Test fun `leaving YouTube cancels any pending short click`() {
        val router = YouTubeGestureRouter()
        startYouTubeFist(router)
        assertNull(router.route(none, 330L, null, false))
        assertNull(router.route(palm, 550L, null, false))
    }

    @Test fun `other shapes cannot secretly trigger YouTube media key`() {
        val router = YouTubeGestureRouter()
        assertNull(router.route(fist, 100L, null, true))
        assertNull(router.route(fist, 1000L, null, true))
        startYouTubeFist(router, 1200L)
        assertNull(router.route(other, 1300L, null, true))
        assertEquals(tap, router.route(other, 1450L, null, true))
        assertNull(router.route(other, 2000L, null, true))
    }
}
