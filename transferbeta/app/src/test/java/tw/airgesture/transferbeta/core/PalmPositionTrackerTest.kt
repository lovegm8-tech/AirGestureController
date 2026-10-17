package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class PalmPositionTrackerTest {
    @Test fun missingOneClassificationDoesNotBlinkOrResetTrail() {
        val tracker = PalmPositionTracker()
        val first = tracker.observe(.3f, .4f, true, 1000L)
        assertNotNull(first)
        val next = tracker.observe(.4f, .5f, true, 1115L)
        assertEquals(2, next!!.trail.size)
        val gap = tracker.observe(null, null, false, 1230L)
        assertNotNull(gap)
        assertEquals(next.trail, gap!!.trail)
        val resumed = tracker.observe(.5f, .6f, true, 1345L)
        assertEquals(3, resumed!!.trail.size)
        assertNull(tracker.observe(null, null, false, 1700L))
    }

    @Test fun invalidPositionNeverCreatesNewTrailAndLongGapResets() {
        val tracker = PalmPositionTracker()
        assertNull(tracker.observe(1.5f, .5f, true, 10L))
        assertEquals(1, tracker.observe(.2f, .2f, true, 100L)!!.trail.size)
        assertEquals(1, tracker.observe(.5f, .5f, true, 750L)!!.trail.size)
        tracker.clear()
        assertNull(tracker.observe(null, null, false, 800L))
    }

    @Test fun trailLengthBoundedAndSmallJitterFiltered() {
        val tracker = PalmPositionTracker()
        tracker.observe(.1f, .1f, true, 100L)
        val stable = tracker.observe(.101f, .101f, true, 200L)
        assertEquals(1, stable!!.trail.size)
        for (i in 0..24) {
            tracker.observe(.2f + .01f * i, .3f, true, 300L + i * 100L)
        }
        val last = tracker.observe(.8f, .8f, true, 3000L)
        assertEquals(16, last!!.trail.size)
    }
}
