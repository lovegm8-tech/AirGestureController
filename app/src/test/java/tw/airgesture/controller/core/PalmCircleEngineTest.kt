package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PalmCircleEngineTest {
    private fun ready(engine: PalmCircleEngine, x: Float = .63f, y: Float = .50f) {
        assertEquals(PalmCircleEngine.Phase.IDLE, engine.observe(x, y, true, 0).phase)
        assertEquals(PalmCircleEngine.Phase.IDLE, engine.observe(x, y, true, 150).phase)
        assertEquals(PalmCircleEngine.Phase.READY, engine.observe(x, y, true, 360).phase)
    }

    private fun runArc(engine: PalmCircleEngine, clockwise: Boolean, endAngle: Int = 390):
        List<PalmCircleEngine.Result> {
        val results = mutableListOf<PalmCircleEngine.Result>()
        for (angle in 30..endAngle step 30) {
            val radians = (angle.toDouble() * PI / 180.0) *
                if (clockwise) 1.0 else -1.0
            val x = (.50 + .13 * cos(radians)).toFloat()
            val y = (.50 + .13 * sin(radians)).toFloat()
            results.add(engine.observe(x, y, true, 360L + angle * 10L / 3L))
        }
        return results
    }

    @Test fun clockwiseCircleIncreasesVolumeOneStepAndShowsClockwiseTrail() {
        val e = PalmCircleEngine()
        ready(e)
        val results = runArc(e, true, 300)
        assertEquals(1, results.count { it.volumeStep == 1 })
        assertEquals(0, results.count { it.volumeStep == -1 })
        assertTrue(results.any { it.phase == PalmCircleEngine.Phase.DRAWING })
        assertTrue(results.last().trail.size >= 9)
        assertTrue(results.last().direction > 0)
    }

    @Test fun anticlockwiseCircleDecreasesMediaVolume() {
        val e = PalmCircleEngine()
        ready(e)
        val results = runArc(e, false, 300)
        assertEquals(1, results.count { it.volumeStep == -1 })
        assertEquals(0, results.count { it.volumeStep == 1 })
        assertTrue(results.last().direction < 0)
    }

    @Test fun continuedClockwiseRotationChangesVolumeGradually() {
        val e = PalmCircleEngine()
        ready(e)
        val results = runArc(e, true, 480)
        val volume = results.filter { it.volumeStep == 1 }
        assertTrue("expected at least two gradual clicks", volume.size >= 2)
        assertTrue("do not advance every frame", volume.size <= 3)
    }

    @Test fun straightDownSwipeFallsBackToOriginalGesture() {
        val e = PalmCircleEngine()
        ready(e, .5f, .5f)
        assertTrue(e.observe(.5f, .44f, true, 460).intercept)
        assertTrue(e.observe(.5f, .38f, true, 560).intercept)
        val result = e.observe(.5f, .32f, true, 660)
        assertTrue(result.cancelledToStraight)
        assertFalse(result.intercept)
        assertEquals(0, result.volumeStep)
    }

    @Test fun noStablePalmDoesNotActivateCircleOrVolume() {
        val e = PalmCircleEngine()
        val outputs = (0..15).map { n ->
            e.observe(.30f + n * .025f, .5f, true, n * 100L)
        }
        assertTrue(outputs.none { it.intercept || it.volumeStep != 0 })
    }

    @Test fun fistOrMissingHandCancelsCircleAndTrailImmediately() {
        val e = PalmCircleEngine()
        ready(e)
        e.observe(.61f, .55f, true, 460)
        val result = e.observe(null, null, false, 600)
        assertEquals(PalmCircleEngine.Phase.IDLE, result.phase)
        assertTrue(result.trail.isEmpty())
        assertEquals(PalmCircleEngine.Phase.IDLE, e.observe(.5f, .5f, true, 760).phase)
    }

    @Test fun almostMotionlessPalmNeverChangesVolume() {
        val e = PalmCircleEngine()
        ready(e)
        repeat(6) { i ->
            val r = e.observe(.632f, .502f, true, 420 + i * 85L)
            assertEquals(0, r.volumeStep)
        }
    }
}
