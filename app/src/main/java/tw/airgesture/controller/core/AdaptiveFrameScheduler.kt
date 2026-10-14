package tw.airgesture.controller.core

/**
 * Rate-limits expensive MediaPipe inference without suspending the camera service.
 * The fast period is retained briefly after seeing a hand to avoid delayed swipes/fists.
 * All timestamps use the same monotonic uptime clock.
 */
class AdaptiveFrameScheduler(mode: Mode = Mode.BALANCED) {
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
    private val handHoldMs = 1200L
    private var lastProcessedMs = Long.MIN_VALUE
    private var lastHandMs = Long.MIN_VALUE

    fun shouldAnalyze(nowMs: Long): Boolean {
        if (lastProcessedMs == Long.MIN_VALUE || nowMs < lastProcessedMs) return true
        val recentlySawHand = lastHandMs != Long.MIN_VALUE &&
            nowMs >= lastHandMs && nowMs - lastHandMs < handHoldMs
        val period = if (recentlySawHand) handPeriodMs else idlePeriodMs
        return nowMs - lastProcessedMs >= period
    }

    fun onAnalyzed(nowMs: Long, foundHand: Boolean) {
        lastProcessedMs = nowMs
        if (foundHand) lastHandMs = nowMs
        else if (lastHandMs != Long.MIN_VALUE && nowMs < lastHandMs) {
            lastHandMs = Long.MIN_VALUE
        }
    }
}
