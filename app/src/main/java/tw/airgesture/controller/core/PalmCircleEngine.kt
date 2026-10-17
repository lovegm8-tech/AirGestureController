package tw.airgesture.controller.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Open-palm trajectory recognizer. All coordinates are camera-normalized,
 * mirrored to user-facing screen X, with Y increasing downwards.
 *
 * The fist/swipe decision engine remains independent. After holding a stable
 * palm for 350ms this recognizer arbitrates the following movement. It defers
 * competing swipe actions briefly; clean straight strokes fall back to them.
 *
 * A full-plane arc (not wrist orientation) controls one media-volume step.
 */
class PalmCircleEngine(
    private val armMs: Long = 350L,
    private val initialSweepDegrees: Float = 240f,
    private val repeatSweepDegrees: Float = 120f,
    private val cooldownMs: Long = 300L
) {
    data class Point(val x: Float, val y: Float)
    enum class Phase { IDLE, READY, DRAWING, VOLUME_UP, VOLUME_DOWN, STRAIGHT }
    data class Result(
        val phase: Phase,
        val x: Float? = null,
        val y: Float? = null,
        val sweepDegrees: Float = 0f,
        val direction: Int = 0,
        val volumeStep: Int = 0,
        val trail: List<Point> = emptyList()
    ) {
        val intercept: Boolean get() = phase == Phase.READY || phase == Phase.DRAWING ||
            phase == Phase.VOLUME_UP || phase == Phase.VOLUME_DOWN
        val cancelledToStraight: Boolean get() = phase == Phase.STRAIGHT
    }

    private var ready = false
    private var straight = false
    private var stableX = 0f
    private var stableY = 0f
    private var stableSince = Long.MIN_VALUE
    private var lastSeen = Long.MIN_VALUE
    private var lastMove = Long.MIN_VALUE
    private var armedAt = Long.MIN_VALUE
    private var sweep = 0.0
    private var absoluteSweep = 0.0
    private var lastStepSweep = 0.0
    private var lastVolumeAt = Long.MIN_VALUE
    private var firedOnce = false
    private var travel = 0f
    private val points = ArrayList<Point>(50)

    fun reset() {
        ready = false
        straight = false
        stableSince = Long.MIN_VALUE
        lastSeen = Long.MIN_VALUE
        lastMove = Long.MIN_VALUE
        armedAt = Long.MIN_VALUE
        sweep = 0.0
        absoluteSweep = 0.0
        lastStepSweep = 0.0
        lastVolumeAt = Long.MIN_VALUE
        firedOnce = false
        travel = 0f
        points.clear()
    }

    fun observe(x: Float?, y: Float?, isConfirmedPalm: Boolean, nowMs: Long): Result {
        if (!isConfirmedPalm || x == null || y == null ||
            x !in 0f..1f || y !in 0f..1f) {
            reset()
            return Result(Phase.IDLE)
        }
        if (lastSeen != Long.MIN_VALUE && (nowMs < lastSeen || nowMs - lastSeen > 560L)) reset()
        lastSeen = nowMs

        if (straight) return Result(Phase.STRAIGHT, x, y)

        if (!ready) {
            if (stableSince == Long.MIN_VALUE ||
                hypot(x - stableX, y - stableY) > .035f) {
                stableX = x
                stableY = y
                stableSince = nowMs
                return Result(Phase.IDLE)
            }
            if (nowMs - stableSince < armMs) return Result(Phase.IDLE)
            ready = true
            armedAt = nowMs
            lastMove = nowMs
            points.add(Point(x, y))
            return snapshot(Phase.READY, x, y)
        }

        val latest = points.last()
        val length = hypot(x - latest.x, y - latest.y)
        if (length >= .014f) {
            val next = Point(x, y)
            points.add(next)
            travel += length
            lastMove = nowMs
            if (points.size >= 3) {
                val a = points[points.size - 3]
                val b = points[points.size - 2]
                val ax = b.x - a.x
                val ay = b.y - a.y
                val bx = next.x - b.x
                val by = next.y - b.y
                val turn = atan2((ax * by - ay * bx).toDouble(),
                    (ax * bx + ay * by).toDouble())
                // Ignore implausible instantaneous zigzags caused by lost tracking.
                if (abs(turn) < 1.65) {
                    sweep += turn
                    absoluteSweep += abs(turn)
                }
            }
            if (points.size > 70) points.removeAt(0)
        }
        if ((points.size > 1 && nowMs - lastMove >= 700L) ||
            nowMs - armedAt > 9_000L) {
            reset()
            return Result(Phase.IDLE)
        }

        val absDegrees = (abs(sweep) * 180.0 / PI).toFloat()
        val straightDistance = hypot(x - points.first().x, y - points.first().y)
        val pointCount = points.size
        val dimensions = bounds()
        val width = dimensions.first
        val height = dimensions.second
        // If a user swipes a straight line, release the held swipe as soon as
        // displacement is clear. No circular-volume event can follow that stroke.
        if (!firedOnce && absDegrees < 15f &&
            straightDistance > .115f && pointCount >= 4) {
            straight = true
            return Result(Phase.STRAIGHT, x, y)
        }

        val dominant = max(width, height)
        val smaller = min(width, height)
        val isRound = width >= .105f && height >= .105f &&
            smaller > 0f && dominant / smaller < 2.4f
        val directionStable = absoluteSweep > 0.1 &&
            abs(sweep) / absoluteSweep >= .78
        val eligible = pointCount >= 7 && isRound &&
            travel >= .32f && directionStable && absDegrees >= initialSweepDegrees
        val direction = if (sweep > 0) 1 else if (sweep < 0) -1 else 0
        val atLeast = if (firedOnce) lastStepSweep + repeatSweepDegrees else initialSweepDegrees.toDouble()
        if (eligible && absDegrees >= atLeast &&
            (lastVolumeAt == Long.MIN_VALUE || nowMs - lastVolumeAt >= cooldownMs)) {
            firedOnce = true
            lastStepSweep = absDegrees.toDouble()
            lastVolumeAt = nowMs
            return snapshot(if (direction > 0) Phase.VOLUME_UP else Phase.VOLUME_DOWN, x, y,
                direction)
        }
        // Even before completion, clearly render the drawn path and direction.
        return snapshot(if (pointCount >= 2) Phase.DRAWING else Phase.READY, x, y, direction)
    }

    private fun bounds(): Pair<Float, Float> {
        if (points.isEmpty()) return 0f to 0f
        var loX = points[0].x
        var hiX = loX
        var loY = points[0].y
        var hiY = loY
        for (p in points) {
            loX = min(loX, p.x)
            hiX = max(hiX, p.x)
            loY = min(loY, p.y)
            hiY = max(hiY, p.y)
        }
        return (hiX - loX) to (hiY - loY)
    }

    private fun snapshot(phase: Phase, x: Float, y: Float, direction: Int = 0): Result =
        Result(
            phase, x, y, (abs(sweep) * 180.0 / PI).toFloat().coerceAtMost(999f),
            direction, if (phase == Phase.VOLUME_UP) 1 else if (phase == Phase.VOLUME_DOWN) -1 else 0,
            points.toList()
        )
}
