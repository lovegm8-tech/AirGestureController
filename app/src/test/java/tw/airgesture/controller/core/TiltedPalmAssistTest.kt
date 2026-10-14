package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class TiltedPalmAssistTest {
    private fun openHand(): FloatArray {
        // Synthetic normalized front-camera landmarks. Only the wrist and
        // four finger MCP/PIP/TIP joints are relevant to this fallback.
        val xy = FloatArray(42) { .5f }
        fun p(id: Int, x: Float, y: Float) {
            xy[id * 2] = x
            xy[id * 2 + 1] = y
        }
        p(0, .50f, .88f)
        p(5, .33f, .49f); p(6, .31f, .39f); p(8, .28f, .12f)
        p(9, .45f, .44f); p(10, .45f, .32f); p(12, .45f, .09f)
        p(13, .58f, .46f); p(14, .59f, .36f); p(16, .61f, .16f)
        p(17, .70f, .55f); p(18, .74f, .46f); p(20, .79f, .24f)
        return xy
    }

    private fun rotate(xy: FloatArray, degrees: Float, mirror: Boolean): FloatArray {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        return xy.clone().apply {
            for (i in 0 until 21) {
                val x = xy[i * 2] - .5f
                val y = xy[i * 2 + 1] - .5f
                val rx = x * cos - y * sin
                val ry = x * sin + y * cos
                this[i * 2] = if (mirror) .5f - rx else .5f + rx
                this[i * 2 + 1] = .5f + ry
            }
        }
    }

    private fun curl(xy: FloatArray, fingerMcp: Int) {
        xy[(fingerMcp + 3) * 2] = xy[fingerMcp * 2]
        xy[(fingerMcp + 3) * 2 + 1] = xy[fingerMcp * 2 + 1]
    }

    @Test fun openHandGeometryWorksWhenRolledAndMirrored() {
        val hand = openHand()
        assertTrue(TiltedPalmAssist.isOpenGeometry(hand))
        for (degrees in listOf(-90f, -50f, -25f, 0f, 40f, 90f)) {
            assertTrue("rotation $degrees", TiltedPalmAssist.isOpenGeometry(rotate(hand, degrees, false)))
            assertTrue("mirrored rotation $degrees", TiltedPalmAssist.isOpenGeometry(rotate(hand, degrees, true)))
        }
    }

    @Test fun foldedFistAndOneFingerDoNotLookLikeOpenPalm() {
        val fist = openHand()
        for (finger in listOf(5, 9, 13, 17)) curl(fist, finger)
        assertFalse(TiltedPalmAssist.isOpenGeometry(fist))
        val pointer = openHand()
        for (finger in listOf(9, 13, 17)) curl(pointer, finger)
        assertFalse(TiltedPalmAssist.isOpenGeometry(pointer))
    }

    @Test fun threeUnobscuredFingersCanProvideFallbackEvidence() {
        val tilted = openHand()
        curl(tilted, 17)
        assertTrue(TiltedPalmAssist.isOpenGeometry(tilted))
    }

    @Test fun optInDoesNotModifyOriginalModelResultsWhenDisabled() {
        val off = TiltedPalmAssist(false)
        val palm = off.resolve("Open_Palm", .85f, null, 0)
        assertTrue(palm.palmAccepted)
        assertTrue(palm.showPalmIndicator)
        assertFalse(palm.useLandmarkFallback)
        val uncertain = off.resolve("None", .90f, openHand(), 100)
        assertFalse(uncertain.palmAccepted)
        assertFalse(uncertain.showPalmIndicator)
    }

    @Test fun uncertainPalmRequiresEvidenceTwice() {
        val assist = TiltedPalmAssist(true)
        val first = assist.resolve("None", .80f, openHand(), 0)
        assertFalse(first.palmAccepted)
        val second = assist.resolve("None", .80f, rotate(openHand(), 45f, true), 100)
        assertTrue(second.useLandmarkFallback)
        assertTrue(second.showPalmIndicator)
    }

    @Test fun neverOverridesConfidentPointingOrFist() {
        val assist = TiltedPalmAssist(true)
        for (gesture in listOf("Closed_Fist", "Pointing_Up", "Victory")) {
            assertFalse(assist.resolve(gesture, .85f, openHand(), 0).palmAccepted)
            assertFalse(assist.resolve(gesture, .85f, openHand(), 100).palmAccepted)
        }
    }

    @Test fun indicatorGraceCannotTriggerGestureWithoutEvidence() {
        val assist = TiltedPalmAssist(true)
        assertTrue(assist.resolve("Open_Palm", .95f, openHand(), 0).palmAccepted)
        val grace = assist.resolve("None", .75f, FloatArray(42), 100)
        assertFalse(grace.palmAccepted)
        assertTrue(grace.showPalmIndicator)
        assertFalse(assist.resolve("None", .75f, null, 150).showPalmIndicator)
        assertFalse(assist.resolve("None", .75f, FloatArray(42), 700).showPalmIndicator)
    }

    @Test fun degenerateOrInvalidLandmarksAreRejected() {
        assertFalse(TiltedPalmAssist.isOpenGeometry(FloatArray(41)))
        assertFalse(TiltedPalmAssist.isOpenGeometry(FloatArray(42)))
        val corrupt = openHand()
        corrupt[0] = Float.NaN
        assertFalse(TiltedPalmAssist.isOpenGeometry(corrupt))
    }
}
