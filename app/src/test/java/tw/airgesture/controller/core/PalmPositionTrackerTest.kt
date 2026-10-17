package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class PalmPositionTrackerTest {
    @Test fun `confirmed palm displays the real normalized position immediately`() {
        val tracker = PalmPositionTracker()
        val value = tracker.observe(.42f, .61f, true, 100L)!!
        assertEquals(.42f, value.x, .0001f)
        assertEquals(.61f, value.y, .0001f)
        assertEquals(1, value.trail.size)
    }

    @Test fun `trail follows movement but does not grow unbounded`() {
        val tracker = PalmPositionTracker()
        repeat(100) { i ->
            val r = tracker.observe(.10f + i * .007f, .55f, true, 100L + i * 100L)!!
            assertTrue(r.trail.size <= 22)
        }
        assertEquals(22, tracker.observe(.95f, .55f, true, 10100L)!!.trail.size)
    }

    @Test fun `small jitter does not keep adding trail points`() {
        val tracker = PalmPositionTracker()
        tracker.observe(.50f, .40f, true, 0L)
        val value = tracker.observe(.502f, .403f, true, 100L)!!
        assertEquals(1, value.trail.size)
        assertEquals(.502f, value.x, .0001f)
    }

    @Test fun `fist and no hand immediately hide palm marker`() {
        val tracker = PalmPositionTracker()
        tracker.observe(.50f, .40f, true, 0L)
        assertNull(tracker.observe(.51f, .4f, false, 100L))
        assertEquals(1, tracker.observe(.6f, .4f, true, 250L)!!.trail.size)
        assertNull(tracker.observe(null, null, false, 330L))
    }

    @Test fun `tracking gap resets trail instead of drawing long ghost strokes`() {
        val tracker = PalmPositionTracker()
        tracker.observe(.20f, .50f, true, 100L)
        val result = tracker.observe(.50f, .60f, true, 950L)!!
        assertEquals(1, result.trail.size)
        assertEquals(.50f, result.x, .0001f)
    }

    @Test fun `reject out of bounds input without any user action`() {
        val tracker = PalmPositionTracker()
        assertNull(tracker.observe(2f, .5f, true, 10L))
        assertNull(tracker.observe(.2f, -.1f, true, 50L))
        assertNull(tracker.observe(null, .2f, true, 90L))
    }
}
