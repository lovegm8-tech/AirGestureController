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
        for (t in listOf(350L,700L,1000L,2000L)) assertNull(e.accept(GestureDecisionEngine.Frame(fist),t))
        e.accept(GestureDecisionEngine.Frame(open,.5f),2200)
        e.accept(GestureDecisionEngine.Frame(open,.5f),2740)
        assertNull(e.accept(GestureDecisionEngine.Frame(fist),2830))
        assertEquals(GestureDecisionEngine.Action.TAP_CENTER,e.accept(GestureDecisionEngine.Frame(fist),2920))
    }
}

