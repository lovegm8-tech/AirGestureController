package tw.airgesture.transferbeta.core

/** Inference throttle only: an open camera sensor still consumes power. */
class AdaptivePowerPolicy(
    private val idleIntervalMs: Long = 950L,
    private val activeIntervalMs: Long = 115L,
    private val activeKeepAliveMs: Long = 4500L
) {
    enum class Mode { STOPPED, ECO, ACTIVE }
    private var enabled = false
    private var screenOn = true
    private var lastInference = Long.MIN_VALUE
    private var activeUntil = Long.MIN_VALUE

    fun start() { enabled = true; lastInference = Long.MIN_VALUE }
    fun stop() { enabled = false }
    fun screenChanged(on: Boolean) {
        screenOn = on
        lastInference = Long.MIN_VALUE
        if (!on) activeUntil = Long.MIN_VALUE
    }
    fun mode(nowMs: Long): Mode = when {
        !enabled || !screenOn -> Mode.STOPPED
        activeUntil != Long.MIN_VALUE && nowMs <= activeUntil -> Mode.ACTIVE
        else -> Mode.ECO
    }
    fun shouldInfer(nowMs: Long): Boolean {
        val interval = when (mode(nowMs)) {
            Mode.STOPPED -> return false
            Mode.ECO -> idleIntervalMs
            Mode.ACTIVE -> activeIntervalMs
        }
        if (lastInference != Long.MIN_VALUE && nowMs >= lastInference &&
            nowMs - lastInference < interval) return false
        lastInference = nowMs
        return true
    }
    fun observeHand(found: Boolean, nowMs: Long) {
        if (found && enabled && screenOn) activeUntil = nowMs + activeKeepAliveMs
    }
}
