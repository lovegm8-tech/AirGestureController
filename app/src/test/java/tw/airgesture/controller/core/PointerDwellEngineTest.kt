package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class PointerDwellEngineTest {
    @Test fun threeSecondHoverClicksOnce() {
        val e = PointerDwellEngine(dwellMs = 3000L)
        assertEquals(0f, e.accept(.4f, .5f, 0)!!.progress)
        assertFalse(e.accept(.4f, .5f, 2999)!!.click)
        val fired = e.accept(.4f, .5f, 3000)!!
        assertTrue(fired.click)
        assertFalse(e.accept(.4f, .5f, 6500)!!.click)
    }

    @Test fun movingFingerResetsCountdown() {
        val e = PointerDwellEngine(dwellMs = 3000L)
        e.accept(.2f, .3f, 0)
        assertFalse(e.accept(.2f, .3f, 1500)!!.click)
        e.accept(.6f, .3f, 1600)
        assertFalse(e.accept(.6f, .3f, 3900)!!.click)
        assertTrue(e.accept(.6f, .3f, 4700)!!.click)
    }

    @Test fun movingAfterClickRearmsButHoldingNeverRepeats() {
        val e = PointerDwellEngine(dwellMs = 3000L)
        e.accept(.3f, .4f, 0)
        assertTrue(e.accept(.3f, .4f, 3000)!!.click)
        assertFalse(e.accept(.3f, .4f, 9000)!!.click)
        e.accept(.8f, .4f, 9100)
        assertFalse(e.accept(.8f, .4f, 10000)!!.click)
        assertTrue(e.accept(.8f, .4f, 12200)!!.click)
    }

    @Test fun lostPointingResetsClickAndProgress() {
        val e = PointerDwellEngine(dwellMs = 3000L)
        e.accept(.3f, .3f, 0)
        assertFalse(e.accept(.3f, .3f, 2900)!!.click)
        e.reset()
        assertFalse(e.accept(.3f, .3f, 3000)!!.click)
        assertTrue(e.accept(.3f, .3f, 6000)!!.click)
    }

    @Test fun userSelectedOneTwoThreeSecondsAllClickAtCorrectThreshold() {
        for (ms in listOf(1000L, 2000L, 3000L)) {
            val e = PointerDwellEngine(dwellMs = ms)
            assertFalse(e.accept(.4f, .5f, 0L)!!.click)
            assertFalse(e.accept(.4f, .5f, ms - 1L)!!.click)
            assertTrue("dwell=$ms", e.accept(.4f, .5f, ms)!!.click)
            assertFalse("never auto repeat", e.accept(.4f, .5f, ms + 5000L)!!.click)
        }
    }

    @Test fun defaultDwellIsTwoSeconds() {
        val e = PointerDwellEngine()
        assertFalse(e.accept(.3f, .5f, 0L)!!.click)
        assertFalse(e.accept(.3f, .5f, 1999L)!!.click)
        assertTrue(e.accept(.3f, .5f, 2000L)!!.click)
    }

    @Test fun invalidCoordinatesNeverClick() {
        val e = PointerDwellEngine(dwellMs = 3000L)
        assertNull(e.accept(Float.NaN, .5f, 0))
        assertNull(e.accept(1.5f, .5f, 3000))
    }
}
