package tw.airgesture.controller.core

import kotlin.math.abs

/** Android-independent state machine so behaviour can be tested without an APK. */
class GestureDecisionEngine(private val options: Options = Options()) {
    enum class Shape { OPEN_PALM, CLOSED_FIST, OTHER, NO_HAND }
    enum class Action { SWIPE_UP, SWIPE_DOWN, TAP_CENTER }
    data class Frame(val shape: Shape, val palmY: Float? = null, val confidence: Float = 1f)
    data class Options(
        val minimumConfidence: Float = .65f,
        val swipeDistance: Float = .13f,
        val cooldownMs: Long = 850L,
        val maximumSwipeDurationMs: Long = 1_500L,
        val minimumSwipeDurationMs: Long = 100L,
        val handLostResetMs: Long = 350L,
        val fistWindowMs: Long = 950L
    )

    private var anchorY: Float? = null
    private var anchorTime = 0L
    private var lastOpenTime = Long.MIN_VALUE
    private var lastHandTime = Long.MIN_VALUE
    private var lastTime = Long.MIN_VALUE
    private var openFrames = 0
    private var fistFrames = 0
    private var blocked = false
    private var cooldownUntil = Long.MIN_VALUE
    private var otherStart = Long.MIN_VALUE

    fun reset() {
        anchorY = null
        anchorTime = 0
        lastOpenTime = Long.MIN_VALUE
        lastHandTime = Long.MIN_VALUE
        lastTime = Long.MIN_VALUE
        openFrames = 0
        fistFrames = 0
        blocked = false
        cooldownUntil = Long.MIN_VALUE
        otherStart = Long.MIN_VALUE
    }

    fun accept(frame: Frame, nowMs: Long): Action? {
        if (lastTime != Long.MIN_VALUE && nowMs < lastTime) reset()
        lastTime = nowMs
        val shape = if (frame.confidence < options.minimumConfidence) Shape.NO_HAND else frame.shape
        if (shape == Shape.NO_HAND) {
            openFrames = 0
            fistFrames = 0
            if (lastHandTime != Long.MIN_VALUE && nowMs - lastHandTime >= options.handLostResetMs) {
                disarm()
                blocked = false
            }
            return null
        }
        lastHandTime = nowMs
        if (shape == Shape.OTHER) {
            openFrames = 0
            fistFrames = 0
            if (otherStart == Long.MIN_VALUE) otherStart = nowMs
            if (nowMs - otherStart >= options.handLostResetMs) {
                disarm()
                blocked = false
            }
            return null
        }
        otherStart = Long.MIN_VALUE
        if (blocked || nowMs < cooldownUntil) return null

        if (shape == Shape.OPEN_PALM) {
            fistFrames = 0
            openFrames++
            lastOpenTime = nowMs
            val y = frame.palmY ?: return null
            if (y < 0f || y > 1f) return null
            if (openFrames < 2) return null
            val anchor = anchorY
            if (anchor == null) {
                anchorY = y
                anchorTime = nowMs
                return null
            }
            val age = nowMs - anchorTime
            if (age > options.maximumSwipeDurationMs) {
                anchorY = y
                anchorTime = nowMs
                return null
            }
            if (age < options.minimumSwipeDurationMs || abs(y - anchor) < options.swipeDistance) return null
            return fire(if (y < anchor) Action.SWIPE_UP else Action.SWIPE_DOWN, nowMs)
        }
        // CLOSED_FIST deliberately requires a recent open palm.
        if (shape == Shape.CLOSED_FIST) {
            openFrames = 0
            if (anchorY == null || lastOpenTime == Long.MIN_VALUE || nowMs - lastOpenTime > options.fistWindowMs) {
                fistFrames = 0
                return null
            }
            fistFrames++
            if (fistFrames >= 2) return fire(Action.TAP_CENTER, nowMs)
        }
        return null
    }

    private fun fire(action: Action, nowMs: Long): Action {
        blocked = true // Hand must leave the frame or relax before another action.
        cooldownUntil = nowMs + options.cooldownMs
        disarm()
        return action
    }

    private fun disarm() {
        anchorY = null
        lastOpenTime = Long.MIN_VALUE
        openFrames = 0
        fistFrames = 0
    }
}
