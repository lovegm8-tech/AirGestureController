package tw.airgesture.transferbeta.core

import kotlin.math.hypot

/**
 * Visual feedback confidence only; NOT a gesture authorization filter.
 * A single MediaPipe false positive must not show "hand detected" or a
 * floating palm position. Two close, plausible 21-point hand observations
 * confirm that the camera is actually tracking a hand.
 *
 * Runs on the same ML frames without requesting additional inference or
 * keeping any camera frame; it stores only two small coordinate pairs.
 */
class HandDisplayGate(
    private val maximumFrameGapMs: Long = 550L,
    private val maximumCenterJump: Float = .28f
) {
    private var previousAt = Long.MIN_VALUE
    private var previousX = Float.NaN
    private var previousY = Float.NaN
    private var consistentFrames = 0

    fun reset() {
        previousAt = Long.MIN_VALUE
        previousX = Float.NaN
        previousY = Float.NaN
        consistentFrames = 0
    }

    fun observe(
        landmarks: FloatArray?,
        centerX: Float?,
        centerY: Float?,
        nowMs: Long
    ): Boolean {
        val x = centerX ?: run { reset(); return false }
        val y = centerY ?: run { reset(); return false }
        if (landmarks == null || landmarks.size < 42 ||
            !x.isFinite() || !y.isFinite() || x !in 0f..1f ||
            y !in 0f..1f || nowMs < 0L) {
            reset()
            return false
        }
        // Reject grossly degenerate or off-screen false hand landmarks.
        // Centers near the screen edges are accepted for upward low starts.
        for (i in 0 until 42) {
            if (!landmarks[i].isFinite() ||
                landmarks[i] !in -.15f..1.15f) {
                reset()
                return false
            }
        }
        fun length(a: Int, b: Int): Float = hypot(
            landmarks[a * 2] - landmarks[b * 2],
            landmarks[a * 2 + 1] - landmarks[b * 2 + 1]
        )
        if (length(5, 17) < .028f || length(0, 9) < .04f) {
            reset()
            return false
        }
        val linked = previousAt != Long.MIN_VALUE &&
            nowMs >= previousAt && nowMs - previousAt <= maximumFrameGapMs &&
            hypot(x - previousX, y - previousY) <= maximumCenterJump
        consistentFrames = if (linked) (consistentFrames + 1).coerceAtMost(3)
            else 1
        previousAt = nowMs
        previousX = x
        previousY = y
        return consistentFrames >= 2
    }
}
