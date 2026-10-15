package tw.airgesture.wakeprobe

/**
 * User-invoked experiment; no unlock bypass. Screen-off palm only requests a
 * screen wake, and palm->fist is accepted only after Android reports unlocked.
 */
class WakeGestureEngine(
    private val wakeHoldMs: Long = 850L,
    private val fistWindowMs: Long = 1800L
) {
    enum class State { WAITING_SCREEN_OFF, WATCHING_PALM, AWAITING_UNLOCK, WAITING_RESUME_PALM, WAITING_FIST, COMPLETED }
    enum class Action { ATTEMPT_WAKE, TAP_TO_RESUME }
    enum class Shape { PALM, FIST, OTHER }

    var state = State.WAITING_SCREEN_OFF
        private set
    private var palmSince: Long = -1L
    private var lastPalm: Long = -1L
    private var palmSamples = 0
    private var fistSamples = 0
    private var lastOpen: Long = -1L

    fun screenOff() {
        state = State.WATCHING_PALM
        resetPalm()
        fistSamples = 0
        lastOpen = -1L
    }

    fun unlocked() {
        state = State.WAITING_RESUME_PALM
        resetPalm()
        fistSamples = 0
        lastOpen = -1L
    }

    fun wakeAttempted() {
        state = State.AWAITING_UNLOCK
        resetPalm()
    }

    fun accept(shape: Shape, nowMs: Long): Action? {
        when (state) {
            State.WATCHING_PALM -> {
                if (shape != Shape.PALM) {
                    resetPalm()
                    return null
                }
                if (palmSince < 0L || lastPalm < 0L || nowMs < lastPalm || nowMs - lastPalm > 350L) {
                    palmSince = nowMs
                    palmSamples = 0
                }
                palmSamples++
                lastPalm = nowMs
                if (palmSamples >= 4 && nowMs - palmSince >= wakeHoldMs) {
                    state = State.AWAITING_UNLOCK
                    return Action.ATTEMPT_WAKE
                }
            }
            State.WAITING_RESUME_PALM -> {
                if (shape == Shape.PALM) {
                    palmSamples++
                    if (palmSamples >= 2) {
                        state = State.WAITING_FIST
                        lastOpen = nowMs
                        fistSamples = 0
                    }
                } else palmSamples = 0
            }
            State.WAITING_FIST -> {
                if (shape == Shape.PALM) {
                    lastOpen = nowMs
                    fistSamples = 0
                } else if (shape == Shape.FIST && lastOpen >= 0L &&
                    nowMs >= lastOpen && nowMs - lastOpen <= fistWindowMs) {
                    fistSamples++
                    if (fistSamples >= 2) {
                        state = State.COMPLETED
                        return Action.TAP_TO_RESUME
                    }
                } else {
                    state = State.WAITING_RESUME_PALM
                    palmSamples = 0
                    fistSamples = 0
                }
            }
            else -> Unit
        }
        return null
    }

    private fun resetPalm() {
        palmSince = -1L
        lastPalm = -1L
        palmSamples = 0
    }
}
