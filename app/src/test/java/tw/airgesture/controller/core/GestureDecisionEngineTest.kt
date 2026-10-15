package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class GestureDecisionEngineTest {
    private val open = GestureDecisionEngine.Shape.OPEN_PALM
    private val fist = GestureDecisionEngine.Shape.CLOSED_FIST
    private val none = GestureDecisionEngine.Shape.NO_HAND

    @Test fun `upward open palm triggers one upward swipe`() {
        val e = GestureDecisionEngine()
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .72f), 0))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .71f), 80))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .62f), 160))
        assertEquals(GestureDecisionEngine.Action.SWIPE_UP, e.accept(GestureDecisionEngine.Frame(open, .51f), 250))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .30f), 350))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .20f), 1500))
    }

    @Test fun `downward open palm triggers swipe down`() {
        val e = GestureDecisionEngine()
        e.accept(GestureDecisionEngine.Frame(open, .30f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .32f), 80)
        assertEquals(GestureDecisionEngine.Action.SWIPE_DOWN, e.accept(GestureDecisionEngine.Frame(open, .51f), 260))
    }

    @Test fun `fist alone does not click`() {
        val e = GestureDecisionEngine()
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 10))
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 100))
    }

    @Test fun `open palm changing to fist clicks once`() {
        val e = GestureDecisionEngine()
        e.accept(GestureDecisionEngine.Frame(open, .5f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .5f), 70)
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 140))
        assertEquals(GestureDecisionEngine.Action.TAP_CENTER, e.accept(GestureDecisionEngine.Frame(fist), 220))
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 320))
    }

    @Test fun `hand out of view allows a new gesture after cooldown`() {
        val e = GestureDecisionEngine()
        e.accept(GestureDecisionEngine.Frame(open, .7f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .7f), 80)
        assertEquals(GestureDecisionEngine.Action.SWIPE_UP, e.accept(GestureDecisionEngine.Frame(open, .45f), 240))
        e.accept(GestureDecisionEngine.Frame(none), 300)
        e.accept(GestureDecisionEngine.Frame(none), 700)
        e.accept(GestureDecisionEngine.Frame(open, .3f), 1200)
        e.accept(GestureDecisionEngine.Frame(open, .3f), 1280)
        assertEquals(GestureDecisionEngine.Action.SWIPE_DOWN, e.accept(GestureDecisionEngine.Frame(open, .55f), 1450))
    }

    @Test fun `slow drift and weak detections do not trigger`() {
        val e = GestureDecisionEngine()
        e.accept(GestureDecisionEngine.Frame(open, .6f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .6f), 100)
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .5f), 1600))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .2f, .2f), 1800))
    }
    @Test fun `steady palm rearms without leaving view`() {
        val e = GestureDecisionEngine(GestureDecisionEngine.Options(cooldownMs = 500))
        e.accept(GestureDecisionEngine.Frame(open, .75f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .75f), 90)
        assertEquals(GestureDecisionEngine.Action.SWIPE_UP, e.accept(GestureDecisionEngine.Frame(open, .50f), 240))
        for ((t,y) in listOf(330L to .40f, 430L to .31f, 630L to .31f, 850L to .31f, 950L to .31f)) {
            assertNull(e.accept(GestureDecisionEngine.Frame(open,y),t))
        }
        assertEquals(GestureDecisionEngine.Action.SWIPE_DOWN, e.accept(GestureDecisionEngine.Frame(open,.52f),1070))
    }

    @Test fun `holding fist does not retrigger click`() {
        val e = GestureDecisionEngine(GestureDecisionEngine.Options(cooldownMs = 500))
        e.accept(GestureDecisionEngine.Frame(open,.5f),0)
        e.accept(GestureDecisionEngine.Frame(open,.5f),90)
        e.accept(GestureDecisionEngine.Frame(fist),160)
        assertEquals(GestureDecisionEngine.Action.TAP_CENTER,e.accept(GestureDecisionEngine.Frame(fist),260))
        for (t in listOf(350L,700L)) assertNull(e.accept(GestureDecisionEngine.Frame(fist),t))
        assertEquals(GestureDecisionEngine.Action.TAP_CENTER, e.accept(GestureDecisionEngine.Frame(fist), 1000L))
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 2000L))
        e.accept(GestureDecisionEngine.Frame(open,.5f),2200)
        e.accept(GestureDecisionEngine.Frame(open,.5f),2740)
        assertNull(e.accept(GestureDecisionEngine.Frame(fist),2830))
        assertEquals(GestureDecisionEngine.Action.TAP_CENTER,e.accept(GestureDecisionEngine.Frame(fist),2920))
    }

    @Test fun `selected long fist durations give exactly one extra click`() {
        for (duration in listOf(500L, 700L, 1000L)) {
            val e = GestureDecisionEngine(
                GestureDecisionEngine.Options(cooldownMs = 1600L,
                    longFistSecondClickMs = duration)
            )
            e.accept(GestureDecisionEngine.Frame(open, .5f), 0L)
            e.accept(GestureDecisionEngine.Frame(open, .5f), 70L)
            assertNull(e.accept(GestureDecisionEngine.Frame(fist), 130L))
            assertEquals(GestureDecisionEngine.Action.TAP_CENTER,
                e.accept(GestureDecisionEngine.Frame(fist), 200L))
            var previous = 200L
            var next = 400L
            while (next < 200L + duration) {
                assertNull("early click duration=$duration",
                    e.accept(GestureDecisionEngine.Frame(fist), next))
                previous = next
                next += 200L
            }
            // Feed one last confirmed fist frame within the allowed frame gap.
            if (200L + duration - previous > 550L) error("test failed to feed frame")
            assertEquals("long fist duration=$duration", GestureDecisionEngine.Action.TAP_CENTER,
                e.accept(GestureDecisionEngine.Frame(fist), 200L + duration))
            assertNull(e.accept(GestureDecisionEngine.Frame(fist), 200L + duration + 200L))
            assertNull(e.accept(GestureDecisionEngine.Frame(fist), 200L + duration + 500L))
        }
    }

    @Test fun `long fist is cancelled by releasing or by a lengthy tracking gap`() {
        fun clickOnce(e: GestureDecisionEngine) {
            e.accept(GestureDecisionEngine.Frame(open, .5f), 0L)
            e.accept(GestureDecisionEngine.Frame(open, .5f), 70L)
            e.accept(GestureDecisionEngine.Frame(fist), 130L)
            assertEquals(GestureDecisionEngine.Action.TAP_CENTER,
                e.accept(GestureDecisionEngine.Frame(fist), 200L))
        }
        val released = GestureDecisionEngine()
        clickOnce(released)
        assertNull(released.accept(GestureDecisionEngine.Frame(open, .5f), 350L))
        assertNull(released.accept(GestureDecisionEngine.Frame(fist), 950L))

        val gap = GestureDecisionEngine()
        clickOnce(gap)
        assertNull(gap.accept(GestureDecisionEngine.Frame(none), 800L))
        assertNull(gap.accept(GestureDecisionEngine.Frame(fist), 950L))

        val noPalm = GestureDecisionEngine()
        assertNull(noPalm.accept(GestureDecisionEngine.Frame(fist), 0L))
        assertNull(noPalm.accept(GestureDecisionEngine.Frame(fist), 1500L))
    }

    @Test fun `brief one-frame loss during held fist keeps second tap safe`() {
        val e = GestureDecisionEngine(GestureDecisionEngine.Options(longFistSecondClickMs = 700L))
        e.accept(GestureDecisionEngine.Frame(open, .5f), 0L)
        e.accept(GestureDecisionEngine.Frame(open, .5f), 70L)
        e.accept(GestureDecisionEngine.Frame(fist), 130L)
        assertEquals(GestureDecisionEngine.Action.TAP_CENTER,
            e.accept(GestureDecisionEngine.Frame(fist), 200L))
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 430L))
        assertNull(e.accept(GestureDecisionEngine.Frame(none), 500L))
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 750L))
        assertEquals(GestureDecisionEngine.Action.TAP_CENTER,
            e.accept(GestureDecisionEngine.Frame(fist), 920L))
        assertNull(e.accept(GestureDecisionEngine.Frame(fist), 1250L))
    }

    @Test fun `rightward palm invokes back without vertical scroll`() {
        val e = GestureDecisionEngine()
        e.accept(GestureDecisionEngine.Frame(open, .55f, palmX = .20f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .55f, palmX = .23f), 100)
        assertEquals(GestureDecisionEngine.Action.SWIPE_BACK,
            e.accept(GestureDecisionEngine.Frame(open, .57f, palmX = .46f), 270))
        assertNull(e.accept(GestureDecisionEngine.Frame(open, .57f, palmX = .70f), 350))
    }

    @Test fun `diagonal and leftward gestures must not invoke back`() {
        val diagonal = GestureDecisionEngine()
        diagonal.accept(GestureDecisionEngine.Frame(open, .20f, palmX = .20f), 0)
        diagonal.accept(GestureDecisionEngine.Frame(open, .20f, palmX = .22f), 100)
        assertNull(diagonal.accept(GestureDecisionEngine.Frame(open, .40f, palmX = .41f), 300))
        val leftward = GestureDecisionEngine()
        leftward.accept(GestureDecisionEngine.Frame(open, .50f, palmX = .70f), 0)
        leftward.accept(GestureDecisionEngine.Frame(open, .50f, palmX = .68f), 100)
        assertNull(leftward.accept(GestureDecisionEngine.Frame(open, .50f, palmX = .30f), 300))
    }

    @Test fun `vertical gesture still works when palm x available`() {
        val e = GestureDecisionEngine()
        e.accept(GestureDecisionEngine.Frame(open, .75f, palmX = .48f), 0)
        e.accept(GestureDecisionEngine.Frame(open, .74f, palmX = .49f), 100)
        assertEquals(GestureDecisionEngine.Action.SWIPE_UP,
            e.accept(GestureDecisionEngine.Frame(open, .50f, palmX = .51f), 260))
    }
}

