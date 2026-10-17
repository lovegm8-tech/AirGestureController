package tw.airgesture.controller.core

import kotlin.math.hypot

/**
 * Visual-only palm location history. No actions, angle thresholds or gesture
 * arbitration. A confirmed palm maps to a light transparent ring and short trail.
 *
 * The overlay intentionally never consumes a swipe, click, or YouTube media key.
 */
class PalmPositionTracker(
    private val maxTrailPoints: Int = 22,
    private val maxGapMs: Long = 520L
) {
    data class Point(val x: Float, val y: Float)
    data class Snapshot(val x: Float, val y: Float, val trail: List<Point>)

    private val history = ArrayDeque<Point>()
    private var lastSeenAt: Long = Long.MIN_VALUE

    fun clear() {
        history.clear()
        lastSeenAt = Long.MIN_VALUE
    }

    fun observe(x: Float?, y: Float?, confirmedPalm: Boolean, nowMs: Long): Snapshot? {
        if (!confirmedPalm || x == null || y == null ||
            x !in 0f..1f || y !in 0f..1f) {
            clear()
            return null
        }
        if (lastSeenAt != Long.MIN_VALUE &&
            (nowMs < lastSeenAt || nowMs - lastSeenAt > maxGapMs)) {
            history.clear()
        }
        lastSeenAt = nowMs
        val latest = history.lastOrNull()
        val next = Point(x, y)
        if (latest == null || hypot(x - latest.x, y - latest.y) >= .008f) {
            history.addLast(next)
            while (history.size > maxTrailPoints) history.removeFirst()
        }
        // Ring uses the actual current hand location; trail skips tiny jitters.
        return Snapshot(x, y, history.toList())
    }
}
