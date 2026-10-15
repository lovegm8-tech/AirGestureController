package tw.airgesture.wakeprobe

import org.junit.Assert.*
import org.junit.Test

class WakeGestureEngineTest {
    private val palm = WakeGestureEngine.Shape.PALM
    private val fist = WakeGestureEngine.Shape.FIST
    private val other = WakeGestureEngine.Shape.OTHER

    @Test fun palmHoldAfterScreenOffTriggersExactlyOneWakeAttempt() {
        val e = WakeGestureEngine()
        e.screenOff()
        assertNull(e.accept(palm, 0))
        assertNull(e.accept(palm, 200))
        assertNull(e.accept(palm, 450))
        assertNull(e.accept(palm, 650))
        assertEquals(WakeGestureEngine.Action.ATTEMPT_WAKE, e.accept(palm, 900))
        assertNull(e.accept(palm, 1100))
        assertEquals(WakeGestureEngine.State.AWAITING_UNLOCK, e.state)
    }

    @Test fun palmFistCannotTapBeforeDeviceIsUnlocked() {
        val e = WakeGestureEngine()
        e.screenOff()
        e.accept(palm, 0)
        e.accept(palm, 200)
        e.accept(palm, 400)
        assertNull(e.accept(fist, 700))
        assertNull(e.accept(fist, 790))
    }

    @Test fun afterUnlockPalmFistTapsOnce() {
        val e = WakeGestureEngine()
        e.unlocked()
        assertNull(e.accept(fist, 0))
        assertNull(e.accept(palm, 100))
        assertNull(e.accept(palm, 200))
        assertNull(e.accept(fist, 300))
        assertEquals(WakeGestureEngine.Action.TAP_TO_RESUME, e.accept(fist, 430))
        assertNull(e.accept(fist, 550))
        assertEquals(WakeGestureEngine.State.COMPLETED, e.state)
    }

    @Test fun interruptedPalmHoldCannotWake() {
        val e = WakeGestureEngine()
        e.screenOff()
        e.accept(palm, 0)
        e.accept(palm, 200)
        e.accept(other, 550)
        e.accept(palm, 800)
        e.accept(palm, 980)
        e.accept(palm, 1170)
        assertNull(e.accept(palm, 1350))
        assertEquals(WakeGestureEngine.Action.ATTEMPT_WAKE, e.accept(palm, 1680))
    }

    @Test fun slowFistAfterExpiredWindowRequiresNewPalm() {
        val e = WakeGestureEngine()
        e.unlocked()
        e.accept(palm, 0)
        e.accept(palm, 130)
        assertNull(e.accept(fist, 2100))
        assertNull(e.accept(fist, 2220))
        e.accept(palm, 2400)
        e.accept(palm, 2490)
        e.accept(fist, 2580)
        assertEquals(WakeGestureEngine.Action.TAP_TO_RESUME, e.accept(fist, 2700))
    }

    @Test fun resettingScreenOffClearsOldResumeState() {
        val e = WakeGestureEngine()
        e.unlocked()
        e.accept(palm, 0)
        e.accept(palm, 100)
        e.screenOff()
        assertNull(e.accept(fist, 200))
        assertEquals(WakeGestureEngine.State.WATCHING_PALM, e.state)
    }
}
