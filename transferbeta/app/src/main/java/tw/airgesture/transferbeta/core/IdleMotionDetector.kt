package tw.airgesture.transferbeta.core

import kotlin.math.abs

/**
 * A low-cost, pure-JVM motion sentinel for long bright-screen idle sessions.
 * Camera remains open so the existing gestures can react immediately.
 *
 * The camera-side caller supplies 16x12 luminance samples instead of asking
 * MediaPipe to run on every frame. A uniform exposure shift is subtracted before
 * counting changed cells (screen reflections can change camera brightness).
 */
class IdleMotionDetector(
    private val minimumChangedCells: Int = 24,
    private val minimumLowerChangedCells: Int = 6,
    private val perCellThreshold: Int = 18,
    private val cooldownMs: Long = 1500L
) {
    private var baseline: IntArray? = null
    private var lastTriggerMs = Long.MIN_VALUE

    fun reset() {
        baseline = null
        lastTriggerMs = Long.MIN_VALUE
    }

    fun observe(samples: IntArray, nowMs: Long): Boolean {
        val previous = baseline
        if (previous == null || previous.size != samples.size ||
            previous.isEmpty()) {
            baseline = samples.copyOf()
            return false
        }
        if (lastTriggerMs != Long.MIN_VALUE && nowMs < lastTriggerMs) {
            reset()
            baseline = samples.copyOf()
            return false
        }
        var uniformShift = 0L
        for (i in samples.indices) uniformShift += samples[i] - previous[i]
        val mean = uniformShift.toFloat() / samples.size
        var changed = 0
        var changedLower = 0
        // The bottom four of twelve camera rows matter most when the hand
        // begins an upward sweep below the selfie-camera center.
        val lowerStart = samples.size * 2 / 3
        for (i in samples.indices) {
            if (abs(samples[i] - previous[i] - mean) >= perCellThreshold) {
                changed++
                if (i >= lowerStart) changedLower++
            }
        }
        baseline = samples.copyOf()
        // A partially visible entering hand may affect few cells, especially
        // below frame center. This is only a hint to RUN the recognizer, never
        // a reason to fire any touch action.
        if (changed < minimumChangedCells &&
            changedLower < minimumLowerChangedCells) return false
        if (lastTriggerMs != Long.MIN_VALUE && nowMs - lastTriggerMs < cooldownMs)
            return false
        lastTriggerMs = nowMs
        return true
    }
}
