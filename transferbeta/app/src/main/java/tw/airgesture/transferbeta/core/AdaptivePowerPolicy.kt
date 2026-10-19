package tw.airgesture.transferbeta.core

/**
 * Adaptive inference scheduling only; the physical camera sensor stays active
 * while the screen is unlocked. This must not block the first recognized hand
 * or an offered file's receive gesture.
 */
class AdaptivePowerPolicy(
    private val idleIntervalMs: Long = 1550L,
    private val deepIdleIntervalMs: Long = 2100L,
    private val deepIdleAfterMs: Long = 60_000L,
    private val activeIntervalMs: Long = 165L,
    private val activeKeepAliveMs: Long = 2200L,
    private val firstHandBurstMs: Long = 900L,
    private val returningHandGapMs: Long = 650L
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
    // Briefly accelerate only when a hand enters/re-enters the camera.
    // Continuous hand tracking returns to normal ACTIVE cadence.
    private var lastHandSeenAt = Long.MIN_VALUE
    private var recognitionBurstUntil = Long.MIN_VALUE

    fun start() {
        enabled = true
        lastInference = Long.MIN_VALUE
        idleSince = Long.MIN_VALUE
        lastHandSeenAt = Long.MIN_VALUE
        recognitionBurstUntil = Long.MIN_VALUE
    }
    fun stop() {
        enabled = false
        receiverAwaiting = false
        idleSince = Long.MIN_VALUE
        activeUntil = Long.MIN_VALUE
        lastHandSeenAt = Long.MIN_VALUE
        recognitionBurstUntil = Long.MIN_VALUE
    }

    /** Incoming transfer offers must remain instantly responsive. */
    fun setReceiverAwaiting(awaiting: Boolean) { receiverAwaiting = awaiting }

    fun screenChanged(on: Boolean) {
        screenOn = on
        lastInference = Long.MIN_VALUE
        idleSince = Long.MIN_VALUE
        if (!on) {
            activeUntil = Long.MIN_VALUE
            lastHandSeenAt = Long.MIN_VALUE
            recognitionBurstUntil = Long.MIN_VALUE
        }
    }

    fun mode(nowMs: Long): Mode = when {
        !enabled || !screenOn -> Mode.STOPPED
        receiverAwaiting -> Mode.ACTIVE
        activeUntil != Long.MIN_VALUE && nowMs <= activeUntil -> Mode.ACTIVE
        else -> Mode.ECO
    }

    /**
     * Cheap pixel motion is sampled during ECO (no MediaPipe run). Balanced
     * and responsive profiles can wake from the first arriving hand; battery-
     * first continues sampling only during deep idle.
     */
    fun shouldSampleIdleMotion(nowMs: Long): Boolean =
        mode(nowMs) == Mode.ECO && (policy != 0 || isDeepIdle(nowMs))

    /** Original AirGestureController-style motion wake-up. Only runs MediaPipe
     * after cheap pixel motion was confirmed, and never fires an action itself. */
    fun isDeepIdle(nowMs: Long): Boolean =
        mode(nowMs) == Mode.ECO && idleSince != Long.MIN_VALUE &&
        nowMs >= idleSince && nowMs - idleSince >= 20_000L

    fun shouldInfer(nowMs: Long, significantMotion: Boolean = false): Boolean {
        // A low-cost, bounded camera-motion hint can schedule ONE early
        // classification in standby. The motion detector enforces its own
        // cooldown and never directly issues actions.
        if (significantMotion && shouldSampleIdleMotion(nowMs) &&
            (lastInference == Long.MIN_VALUE || nowMs < lastInference ||
                nowMs - lastInference >= 240L)) {
            lastInference = nowMs
            return true
        }
        val interval = when (mode(nowMs)) {
            Mode.STOPPED -> return false
            Mode.ACTIVE -> when(policy){
                0 -> 220L
                2 -> 115L
                else -> if (recognitionBurstUntil != Long.MIN_VALUE &&
                    nowMs <= recognitionBurstUntil) 115L else activeIntervalMs
            }
            Mode.ECO -> {
                if (idleSince == Long.MIN_VALUE || nowMs < idleSince)
                    idleSince = nowMs
                if(nowMs-idleSince>=deepIdleAfterMs)
                    when(policy){0->2600L;2->1250L;else->deepIdleIntervalMs}
                else when(policy){0->1950L;2->950L;else->idleIntervalMs}
            }
        }
        if (lastInference != Long.MIN_VALUE && nowMs >= lastInference &&
            nowMs - lastInference < interval) return false
        lastInference = nowMs
        return true
    }

    fun observeHand(found: Boolean, nowMs: Long) {
        if (found && enabled && screenOn) {
            // The first hand can be detected before Open_Palm / Closed_Fist
            // confidence settles. A short burst reduces the NEXT sample wait.
            val returning = lastHandSeenAt == Long.MIN_VALUE ||
                nowMs < lastHandSeenAt ||
                nowMs - lastHandSeenAt >= returningHandGapMs
            if (returning) recognitionBurstUntil = nowMs + firstHandBurstMs
            lastHandSeenAt = nowMs
            idleSince = nowMs
            activeUntil = nowMs + activeKeepAliveMs
        }
    }
}
