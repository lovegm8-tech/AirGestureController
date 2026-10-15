package tw.airgesture.controller.core

/** Short screen-off camera windows trade wake latency for sensor power use. */
object WakePowerSchedule {
    const val SCAN_WINDOW_MS = 3000L
    const val REST_WINDOW_MS = 5000L
    const val MAX_SCAN_EXTENSION_MS = 2200L
    const val HAND_RECENT_MS = 600L
    const val MAX_SESSION_MS = 10 * 60 * 1000L

    fun shouldExtendScan(nowMs: Long, scanStartMs: Long, lastHandMs: Long): Boolean =
        scanStartMs >= 0L && nowMs >= scanStartMs &&
            nowMs - scanStartMs <= SCAN_WINDOW_MS + MAX_SCAN_EXTENSION_MS &&
            lastHandMs >= 0L && nowMs >= lastHandMs && nowMs - lastHandMs <= HAND_RECENT_MS

    fun expired(nowMs: Long, startedMs: Long): Boolean =
        startedMs >= 0L && nowMs >= startedMs && nowMs - startedMs >= MAX_SESSION_MS
}
