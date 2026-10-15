package tw.airgesture.controller.core

import kotlin.math.abs

/**
 * Pure JVM cursor controller. Coordinates are normalized to the camera image, already
 * mirrored for front-facing hand movement. A click requires a continuously stable
 * pointing gesture for the selected dwell time; holding after click never repeats the click.
 */
class PointerDwellEngine(
    private val dwellMs: Long = 2000L,
    private val holdRadius: Float = .045f,
    private val rearmDistance: Float = .12f
) {
    data class Result(val x: Float, val y: Float, val progress: Float, val click: Boolean)
    private var anchorX: Float? = null
    private var anchorY: Float? = null
    private var anchorTime: Long = 0
    private var lastTime: Long = Long.MIN_VALUE
    private var smoothX: Float? = null
    private var smoothY: Float? = null
    private var clickX: Float = 0f
    private var clickY: Float = 0f
    private var locked = false

    fun reset() {
        anchorX = null
        anchorY = null
        lastTime = Long.MIN_VALUE
        smoothX = null
        smoothY = null
        locked = false
    }

    /** Call only when index-finger-only pointing has been confidently detected. */
    fun accept(x: Float, y: Float, nowMs: Long): Result? {
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) {
            reset()
            return null
        }
        if (lastTime != Long.MIN_VALUE && nowMs < lastTime) reset()
        lastTime = nowMs
        val px = (smoothX?.let { it * .55f + x * .45f } ?: x).coerceIn(0f, 1f)
        val py = (smoothY?.let { it * .55f + y * .45f } ?: y).coerceIn(0f, 1f)
        smoothX = px
        smoothY = py

        if (locked) {
            if (abs(x - clickX) > rearmDistance || abs(y - clickY) > rearmDistance) {
                locked = false
                anchorX = x
                anchorY = y
                anchorTime = nowMs
            } else return Result(px, py, 0f, false)
        }

        val ax = anchorX
        val ay = anchorY
        if (ax == null || ay == null || abs(x - ax) > holdRadius || abs(y - ay) > holdRadius) {
            anchorX = x
            anchorY = y
            anchorTime = nowMs
            return Result(px, py, 0f, false)
        }
        val elapsed = (nowMs - anchorTime).coerceAtLeast(0L)
        if (elapsed >= dwellMs) {
            locked = true
            clickX = x
            clickY = y
            return Result(px, py, 1f, true)
        }
        return Result(px, py, elapsed.toFloat() / dwellMs, false)
    }
}
