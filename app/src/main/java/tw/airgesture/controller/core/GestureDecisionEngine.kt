package tw.airgesture.controller.core

import kotlin.math.abs

/** Android-independent state machine: steady-palm rearm can be tested on the JVM. */
class GestureDecisionEngine(private val options: Options = Options()) {
    enum class Shape { OPEN_PALM, CLOSED_FIST, OTHER, NO_HAND }
    enum class Action { SWIPE_UP, SWIPE_DOWN, SWIPE_BACK, TAP_CENTER }
    data class Frame(val shape: Shape, val palmY: Float? = null, val confidence: Float = 1f, val palmX: Float? = null)
    data class Options(
        val minimumConfidence: Float = .65f,
        val swipeDistance: Float = .13f,
        val backSwipeDistance: Float = .17f,
        val cooldownMs: Long = 850L,
        val maximumSwipeDurationMs: Long = 1_500L,
        val minimumSwipeDurationMs: Long = 100L,
        val handLostResetMs: Long = 350L,
        val fistWindowMs: Long = 950L,
        val stillToRearmMs: Long = 500L,
        val stillTolerance: Float = .035f,
        // Opt-in: capture the first valid palm position and bridge brief detection gaps.
        val fastSwipeAssistEnabled: Boolean = false,
        val fastPalmGapMs: Long = 260L
    )

    private var anchorY: Float? = null
    private var anchorX: Float? = null
    private var anchorTime = 0L
    private var lastOpenTime = Long.MIN_VALUE
    private var lastHandTime = Long.MIN_VALUE
    private var lastTime = Long.MIN_VALUE
    private var openFrames = 0
    private var fistFrames = 0
    private var fistArmed = false
    private var blocked = false
    private var cooldownUntil = Long.MIN_VALUE
    private var otherStart = Long.MIN_VALUE
    private var settleY: Float? = null
    private var settleSince = Long.MIN_VALUE

    fun reset() {
        disarm()
        lastHandTime = Long.MIN_VALUE
        lastTime = Long.MIN_VALUE
        blocked = false
        cooldownUntil = Long.MIN_VALUE
        otherStart = Long.MIN_VALUE
        clearSettling()
    }

    fun accept(frame: Frame, nowMs: Long): Action? {
        if (lastTime != Long.MIN_VALUE && nowMs < lastTime) reset()
        lastTime = nowMs
        val shape = if (frame.confidence < options.minimumConfidence) Shape.NO_HAND else frame.shape
        if (shape == Shape.NO_HAND) {
            // A single blurry frame during a fast swipe must not erase the
            // already verified origin. We never create an action on a lost frame:
            // a new, valid open palm endpoint is still required.
            val bridge = options.fastSwipeAssistEnabled && !blocked &&
                anchorY != null && lastOpenTime != Long.MIN_VALUE &&
                nowMs >= lastOpenTime && nowMs - lastOpenTime <= options.fastPalmGapMs
            if (!bridge) {
                disarm()
                clearSettling()
            } else {
                fistFrames = 0
            }
            if (lastHandTime != Long.MIN_VALUE && nowMs - lastHandTime >= options.handLostResetMs) {
                blocked = false
            }
            return null
        }
        lastHandTime = nowMs
        if (shape == Shape.OTHER) {
            disarm()
            clearSettling()
            if (otherStart == Long.MIN_VALUE) otherStart = nowMs
            if (nowMs - otherStart >= options.handLostResetMs) blocked = false
            return null
        }
        otherStart = Long.MIN_VALUE
        // Rearm once the open palm is steady for 0.5s and the cooldown has ended.
        if (blocked) {
            if (shape != Shape.OPEN_PALM || frame.palmY == null || frame.palmY !in 0f..1f) {
                clearSettling()
                return null
            }
            val y = frame.palmY
            if (settleY == null || abs(y - settleY!!) > options.stillTolerance) {
                settleY = y
                settleSince = nowMs
                return null
            }
            if (nowMs - settleSince < options.stillToRearmMs || nowMs < cooldownUntil) return null
            blocked = false
            clearSettling()
            anchorY = y // New origin prevents continued travel from duplicating swipes.
            anchorX = frame.palmX
            anchorTime = nowMs
            lastOpenTime = nowMs
            openFrames = 2
            fistFrames = 0
            fistArmed = true
            return null
        }
        if (nowMs < cooldownUntil) return null

        if (shape == Shape.OPEN_PALM) {
            fistFrames = 0
            val y = frame.palmY ?: return null
            if (y !in 0f..1f) return null
            if (options.fastSwipeAssistEnabled && lastOpenTime != Long.MIN_VALUE &&
                (nowMs < lastOpenTime || nowMs - lastOpenTime > options.fastPalmGapMs)) {
                // Do not connect separate appearances of a hand into one swipe.
                disarm()
            }
            openFrames++
            lastOpenTime = nowMs
            if (options.fastSwipeAssistEnabled && anchorY == null) {
                // Start at the FIRST confirmed palm frame, not the second.
                // Action and fist still require at least two valid palm frames.
                anchorY = y
                anchorX = frame.palmX
                anchorTime = nowMs
                return null
            }
            if (openFrames >= 2) fistArmed = true
            if (openFrames < 2) return null
            val anchor = anchorY
            if (anchor == null) {
                anchorY = y
                anchorX = frame.palmX
                anchorTime = nowMs
                return null
            }
            val age = nowMs - anchorTime
            if (age > options.maximumSwipeDurationMs) {
                anchorY = y
                anchorX = frame.palmX
                anchorTime = nowMs
                return null
            }
            if (age < options.minimumSwipeDurationMs) return null
            val dy = y - anchor
            val dx = if (frame.palmX != null && anchorX != null) frame.palmX - anchorX!! else 0f
            // Only a clear user-right motion can invoke Android edge-back.
            if (dx >= options.backSwipeDistance && dx > abs(dy) * 1.45f) {
                return fire(Action.SWIPE_BACK, nowMs)
            }
            if (abs(dy) < options.swipeDistance || abs(dy) < abs(dx) * 1.10f) return null
            return fire(if (dy < 0f) Action.SWIPE_UP else Action.SWIPE_DOWN, nowMs)
        }
        // CLOSED_FIST: deliberately require a recent open palm rather than any arbitrary fist.
        if (shape == Shape.CLOSED_FIST) {
            openFrames = 0
            if (anchorY == null || (options.fastSwipeAssistEnabled && !fistArmed) || lastOpenTime == Long.MIN_VALUE || nowMs - lastOpenTime > options.fistWindowMs) {
                fistFrames = 0
                return null
            }
            fistFrames++
            if (fistFrames >= 2) return fire(Action.TAP_CENTER, nowMs)
        }
        return null
    }

    private fun fire(action: Action, nowMs: Long): Action {
        blocked = true
        cooldownUntil = nowMs + options.cooldownMs
        disarm()
        clearSettling()
        return action
    }

    private fun clearSettling() {
        settleY = null
        settleSince = Long.MIN_VALUE
    }

    private fun disarm() {
        anchorY = null
        anchorX = null
        anchorTime = 0L
        lastOpenTime = Long.MIN_VALUE
        openFrames = 0
        fistFrames = 0
        fistArmed = false
    }
}
