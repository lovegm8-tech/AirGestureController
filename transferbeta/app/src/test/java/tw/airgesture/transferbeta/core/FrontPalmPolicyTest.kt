package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class FrontPalmPolicyTest {
    private fun hand(thumbX: Float): FloatArray = FloatArray(42).apply {
        for (i in 0 until 21) {
            this[i * 2] = .50f
            this[i * 2 + 1] = .62f
        }
        this[0] = .50f; this[1] = .82f
        this[10] = .38f; this[11] = .50f // index MCP, 5
        this[18] = .50f; this[19] = .43f // middle MCP, 9
        this[34] = .62f; this[35] = .50f // pinky MCP, 17
        for (i in 2..4) {
            this[i * 2] = thumbX
            this[i * 2 + 1] = .62f
        }
    }

    private fun world(edgeOn: Boolean = false): FloatArray = FloatArray(63).apply {
        // wrist at origin; index and pinky MCP form the palm plane.
        this[5 * 3] = if (edgeOn) -.001f else -.04f
        this[5 * 3 + 1] = -.08f
        this[5 * 3 + 2] = if (edgeOn) .05f else 0f
        this[17 * 3] = if (edgeOn) .001f else .04f
        this[17 * 3 + 1] = -.08f
        this[17 * 3 + 2] = if (edgeOn) -.05f else 0f
    }

    private fun mirror(src: FloatArray): FloatArray = src.copyOf().apply {
        for (i in 0 until 21) this[i * 2] = 1f - src[i * 2]
    }

    @Test fun frontPalmAllowsSwipeStartForEitherReportedHand() {
        assertTrue(FrontPalmPolicy.facesCamera(hand(.79f), world(), "Right", .94f))
        assertTrue(FrontPalmPolicy.facesCamera(mirror(hand(.79f)), world(),
            "Left", .94f))
    }

    @Test fun backOfHandCannotStartSwipe() {
        assertFalse(FrontPalmPolicy.facesCamera(hand(.25f), world(), "Right", .94f))
        assertFalse(FrontPalmPolicy.facesCamera(mirror(hand(.25f)), world(),
            "Left", .94f))
    }

    @Test fun unmirroredCameraInputUsesPhysicalHandedness() {
        // Regression: v1.0.14 reversed this and treated the back as palm.
        val physicalRightFront = hand(.79f)
        val physicalRightBack = hand(.25f)
        assertTrue(FrontPalmPolicy.facesCamera(physicalRightFront, world(), "Right", .94f))
        assertFalse(FrontPalmPolicy.facesCamera(physicalRightBack, world(), "Right", .94f))
        // If the pixels are mirrored, the model's handedness flips along
        // with the thumb side, but the palm/back decision is unchanged.
        assertTrue(FrontPalmPolicy.facesCamera(mirror(physicalRightFront), world(),
            "Left", .94f))
        assertFalse(FrontPalmPolicy.facesCamera(mirror(physicalRightBack), world(),
            "Left", .94f))
        // An incorrect handedness/geometry combination must fail closed.
        assertFalse(FrontPalmPolicy.facesCamera(physicalRightFront, world(),
            "Left", .94f))
    }

    @Test fun rollingHandInTheImageStillRecognizesFront() {
        val original = hand(.79f)
        val rotated = original.copyOf()
        for (i in 0 until 21) {
            val x = original[i * 2] - .5f
            val y = original[i * 2 + 1] - .5f
            rotated[i * 2] = .5f - y
            rotated[i * 2 + 1] = .5f + x
        }
        assertTrue(FrontPalmPolicy.facesCamera(rotated, world(), "Right", .95f))
    }

    @Test fun edgeOnOrUnknownHandFailsClosed() {
        assertFalse(FrontPalmPolicy.facesCamera(hand(.79f), world(true), "Right", .94f))
        assertFalse(FrontPalmPolicy.facesCamera(hand(.79f), world(), "Right", .54f))
        assertFalse(FrontPalmPolicy.facesCamera(hand(.79f), null, "Right", .99f))
        assertFalse(FrontPalmPolicy.facesCamera(null, world(), "Right", .99f))
        assertFalse(FrontPalmPolicy.facesCamera(hand(.79f), world(), "Unknown", .99f))
    }
}
