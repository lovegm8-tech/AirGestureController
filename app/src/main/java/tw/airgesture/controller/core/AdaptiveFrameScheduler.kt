package tw.airgesture.controller.core

/**
 * MediaPipe frame gate. In SMART mode it keeps idle recognition responsive for
 * the first 20 seconds, then backs off to a low-frequency standby and returns
 * to 100 ms immediately when a hand appears. Never unbinds the camera.
 *
 * Legacy mode intentionally retains the exact v0.2.9 behavior for rollback.
 */
class AdaptiveFrameScheduler(
    mode: Mode = Mode.BALANCED,
    private val smartPower: Boolean = false,
    private val motionStandby: Boolean = false
) {
    enum class Mode(val idlePeriodMs: Long, val handPeriodMs: Long) {
        SAVER(333L, 140L),
        BALANCED(200L, 100L),
        RESPONSIVE(110L, 85L);

        companion object {
            fun fromIndex(index: Int): Mode = values().getOrElse(index) { BALANCED }
        }
    }

    private val idlePeriodMs = mode.idlePeriodMs
    private val handPeriodMs = mode.handPeriodMs
    private val handHoldMs = if (smartPower) 4500L else 1200L
    private var lastProcessedMs = Long.MIN_VALUE
    private var lastHandMs = Long.MIN_VALUE
    private var idleStartMs = Long.MIN_VALUE

    /** Camera-side 16x12 sampler should run only after >20s without a hand. */
    fun isDeepIdle(nowMs: Long): Boolean =
        smartPower && motionStandby &&
            idleStartMs != Long.MIN_VALUE && nowMs >= idleStartMs &&
            nowMs - idleStartMs >= 20_000L &&
            (lastHandMs == Long.MIN_VALUE || nowMs - lastHandMs >= handHoldMs)

    fun shouldAnalyze(nowMs: Long, significantMotion: Boolean = false): Boolean {
        if (lastProcessedMs == Long.MIN_VALUE || nowMs < lastProcessedMs) return true
        val handRecent = lastHandMs != Long.MIN_VALUE &&
            nowMs >= lastHandMs && nowMs - lastHandMs < handHoldMs
        // Motion means an actual front-camera scene change, NOT a confirmed hand.
        // A full MediaPipe verification is still required to execute any action.
        if (isDeepIdle(nowMs) && significantMotion &&
            nowMs - lastProcessedMs >= 240L) return true
        val period = if (!smartPower) {
            if (handRecent) handPeriodMs else idlePeriodMs
        } else if (handRecent) {
            100L
        } else {
            if (idleStartMs == Long.MIN_VALUE || idleStartMs > nowMs)
                idleStartMs = nowMs
            if (nowMs - idleStartMs >= 20_000L)
                (if (motionStandby) 1500L else 900L)
            else 350L
        }
        return nowMs - lastProcessedMs >= period
    }

    fun onAnalyzed(nowMs: Long, foundHand: Boolean) {
        if (lastProcessedMs != Long.MIN_VALUE && nowMs < lastProcessedMs) {
            lastHandMs = Long.MIN_VALUE
            idleStartMs = Long.MIN_VALUE
        }
        lastProcessedMs = nowMs
        if (foundHand) {
            lastHandMs = nowMs
            idleStartMs = Long.MIN_VALUE
        } else if (lastHandMs != Long.MIN_VALUE && nowMs < lastHandMs) {
            lastHandMs = Long.MIN_VALUE
        }
        if (smartPower && !foundHand && lastHandMs == Long.MIN_VALUE &&
            idleStartMs == Long.MIN_VALUE) idleStartMs = nowMs
    }
}
