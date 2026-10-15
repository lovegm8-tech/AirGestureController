package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class WakePalmDwellEngineTest {
    @Test fun stableOpenPalmAwakensOnlyOnce() {
        val e = WakePalmDwellEngine()
        assertFalse(e.accept(true, 0L))
        assertFalse(e.accept(true, 320L))
        assertFalse(e.accept(true, 650L))
        assertTrue(e.accept(true, 910L))
        assertFalse(e.accept(true, 1200L))
    }

    @Test fun partialHoldAndNonPalmCannotWake() {
        val e = WakePalmDwellEngine()
        e.accept(true, 0)
        e.accept(true, 250)
        assertFalse(e.accept(false, 410))
        assertFalse(e.accept(true, 550))
        assertFalse(e.accept(true, 820))
        assertFalse(e.accept(true, 1110))
        assertTrue(e.accept(true, 1460))
    }

    @Test fun largeFrameGapResetsDwell() {
        val e = WakePalmDwellEngine()
        e.accept(true, 0)
        e.accept(true, 200)
        assertFalse(e.accept(true, 900))
        assertFalse(e.accept(true, 1150))
        assertFalse(e.accept(true, 1360))
        assertTrue(e.accept(true, 1800))
    }

    @Test fun resetEnablesNewCycle() {
        val e = WakePalmDwellEngine()
        e.accept(true, 0)
        e.accept(true, 300)
        assertTrue(e.accept(true, 900))
        e.reset()
        assertFalse(e.accept(true, 1200))
        assertFalse(e.accept(true, 1500))
        assertTrue(e.accept(true, 2100))
    }
}
