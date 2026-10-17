package tw.airgesture.transferbeta.core

/**
 * Adaptive inference scheduling only; the physical camera sensor stays active
 * while the screen is unlocked. This must not block the first recognized hand
 * or an offered file's receive gesture.
 */
class AdaptivePowerPolicy(
    private val idleIntervalMs: Long = 950L,
    private val deepIdleIntervalMs: Long = 1250L,
    private val deepIdleAfterMs: Long = 60_000L,
    private val activeIntervalMs: Long = 115L,
    private val activeKeepAliveMs: Long = 2200L
) {
    enum class Mode { STOPPED, ECO, ACTIVE }
    @Volatile private var policy=1
    fun setPowerMode(mode:Int){policy=mode.coerceIn(0,2)}
    @Volatile private var enabled = false
    @Volatile private var screenOn = true
    @Volatile private var receiverAwaiting = false
    private var lastInference = Long.MIN_VALUE
    @Volatile private var activeUntil = Long.MIN_VALUE
    @Volatile private var idleSince = Long.MIN_VALUE

    fun start() {
        enabled = true
        lastInference = Long.MIN_VALUE
        idleSince = Long.MIN_VALUE
    }
    fun stop() {
        enabled = false
        receiverAwaiting = false
        idleSince = Long.MIN_VALUE
        activeUntil = Long.MIN_VALUE
    }

    /** Incoming transfer offers must remain instantly responsive. */
    fun setReceiverAwaiting(awaiting: Boolean) { receiverAwaiting = awaiting }

    fun screenChanged(on: Boolean) {
        screenOn = on
        lastInference = Long.MIN_VALUE
        idleSince = Long.MIN_VALUE
        if (!on) activeUntil = Long.MIN_VALUE
    }

    fun mode(nowMs: Long): Mode = when {
        !enabled || !screenOn -> Mode.STOPPED
        receiverAwaiting -> Mode.ACTIVE
        activeUntil != Long.MIN_VALUE && nowMs <= activeUntil -> Mode.ACTIVE
        else -> Mode.ECO
    }

    fun shouldInfer(nowMs: Long): Boolean {
        val interval = when (mode(nowMs)) {
            Mode.STOPPED -> return false
            Mode.ACTIVE -> when(policy){0->170L;2->90L;else->activeIntervalMs}
            Mode.ECO -> {
                if (idleSince == Long.MIN_VALUE || nowMs < idleSince)
                    idleSince = nowMs
                if(nowMs-idleSince>=deepIdleAfterMs)
                    when(policy){0->1900L;2->950L;else->deepIdleIntervalMs}
                else when(policy){0->1350L;2->650L;else->idleIntervalMs}
            }
        }
        if (lastInference != Long.MIN_VALUE && nowMs >= lastInference &&
            nowMs - lastInference < interval) return false
        lastInference = nowMs
        return true
    }

    fun observeHand(found: Boolean, nowMs: Long) {
        if (found && enabled && screenOn) {
            idleSince = nowMs
            activeUntil = nowMs + activeKeepAliveMs
        }
    }
}
