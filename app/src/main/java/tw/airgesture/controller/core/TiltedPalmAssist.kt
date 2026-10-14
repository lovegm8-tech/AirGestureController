package tw.airgesture.controller.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Optional, conservative fallback for a palm rolled within the camera image.
 *
 * Uses relative finger-bone lengths/angles (not image-up vs image-down, and not
 * MediaPipe handedness), so mirroring or rotating the same pose gives the same result.
 * This is only a fallback when the original Open_Palm classifier does not pass.
 *
 * Coordinates are MediaPipe normalized x,y pairs, [x0,y0,...,x20,y20].
 * It cannot recover landmarks obscured by a strongly edge-on palm.
 */
class TiltedPalmAssist(private val enabled: Boolean = false) {
    data class Decision(
        val palmAccepted: Boolean,
        val useLandmarkFallback: Boolean,
        val showPalmIndicator: Boolean
    )

    private var consecutiveGeometryFrames = 0
    private var previousFrameMs = Long.MIN_VALUE
    private var lastAcceptedPalmMs = Long.MIN_VALUE

    fun resolve(
        category: String?,
        score: Float,
        landmarks: FloatArray?,
        nowMs: Long
    ): Decision {
        val modelPalm = category == "Open_Palm" && score >= MODEL_THRESHOLD
        if (!enabled) return Decision(modelPalm, false, modelPalm)

        if (previousFrameMs != Long.MIN_VALUE &&
            (nowMs < previousFrameMs || nowMs - previousFrameMs > MAX_GAP_MS)) {
            consecutiveGeometryFrames = 0
            lastAcceptedPalmMs = Long.MIN_VALUE
        }
        previousFrameMs = nowMs

        if (modelPalm) {
            consecutiveGeometryFrames = 0
            lastAcceptedPalmMs = nowMs
            return Decision(true, false, true)
        }

        // Protect fist taps, finger cursor, and explicit non-palm gestures.
        val confidentOther = score >= PROTECTED_GESTURE_THRESHOLD &&
            category != null && PROTECTED_GESTURES.contains(category)
        if (confidentOther) {
            consecutiveGeometryFrames = 0
            return Decision(false, false, false)
        }

        val validGeometry = landmarks != null && isOpenGeometry(landmarks)
        if (validGeometry) {
            consecutiveGeometryFrames = (consecutiveGeometryFrames + 1).coerceAtMost(3)
        } else {
            consecutiveGeometryFrames = 0
        }

        val recentPalm = lastAcceptedPalmMs != Long.MIN_VALUE &&
            nowMs >= lastAcceptedPalmMs && nowMs - lastAcceptedPalmMs <= CONTINUITY_MS
        // Two consecutive geometry observations, or one following a recently
        // trustworthy palm. Never invent landmarks or act on stale coordinates.
        val fallback = validGeometry && (consecutiveGeometryFrames >= 2 || recentPalm)
        if (fallback) lastAcceptedPalmMs = nowMs

        // Brief grace affects the indicator ONLY, not the action engine.
        val indicator = fallback || (
            landmarks != null && recentPalm && !confidentOther
        )
        return Decision(fallback, fallback, indicator)
    }

    companion object {
        private const val MODEL_THRESHOLD = .65f
        private const val PROTECTED_GESTURE_THRESHOLD = .60f
        private const val MAX_GAP_MS = 550L
        private const val CONTINUITY_MS = 250L
        private val PROTECTED_GESTURES = setOf(
            "Closed_Fist", "Pointing_Up", "Victory", "Thumb_Up", "Thumb_Down", "ILoveYou"
        )

        /**
         * A palm-like silhouette must have at least 3 of 4 long fingers extended.
         * The ratios are invariant to translation, 2D rotation, mirror and scale.
         */
        fun isOpenGeometry(points: FloatArray): Boolean {
            if (points.size < 42) return false
            for (i in 0 until 42) {
                if (!points[i].isFinite() || points[i] < -.10f || points[i] > 1.10f) {
                    return false
                }
            }

            fun distance(a: Int, b: Int): Float {
                val dx = points[a * 2] - points[b * 2]
                val dy = points[a * 2 + 1] - points[b * 2 + 1]
                return sqrt(dx * dx + dy * dy)
            }

            // Reject an impossibly small / degenerate projected palm.
            if (distance(5, 17) < .045f || distance(0, 9) < .060f) return false

            var straight = 0
            for (mcp in intArrayOf(5, 9, 13, 17)) {
                val pip = mcp + 1
                val tip = mcp + 3
                val ax = points[pip * 2] - points[mcp * 2]
                val ay = points[pip * 2 + 1] - points[mcp * 2 + 1]
                val bx = points[tip * 2] - points[mcp * 2]
                val by = points[tip * 2 + 1] - points[mcp * 2 + 1]
                val fingerBaseSq = ax * ax + ay * ay
                if (fingerBaseSq < .000035f) continue
                val forward = (ax * bx + ay * by) / fingerBaseSq
                val sideways = abs(ax * by - ay * bx) / fingerBaseSq
                if (forward >= 1.55f && sideways <= 1.10f &&
                    distance(mcp, tip) >= distance(mcp, pip) * 1.65f) {
                    straight++
                }
            }
            return straight >= 3
        }
    }
}
