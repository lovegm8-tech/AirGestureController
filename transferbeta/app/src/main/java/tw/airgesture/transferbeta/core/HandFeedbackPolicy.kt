package tw.airgesture.transferbeta.core

/** Hand tracking feedback, independent from transfer status.
 * A visible hand is NOT a complete send or receive gesture. */
enum class HandHint { NONE, HAND_SEEN, OPEN_PALM, CLOSED_FIST }

class HandFeedbackPolicy(
    private val heartbeatMs: Long = 750L,
    private val lostGraceMs: Long = 700L,
    private val weakClassificationGraceMs: Long = 320L
) {
    private var lastSeenAt: Long = Long.MIN_VALUE
    private var lastEmittedAt: Long = Long.MIN_VALUE
    private var lastHint = HandHint.NONE
    private var lastClassifiedAt = Long.MIN_VALUE

    fun observe(found: Boolean, pose: String, nowMs: Long): HandHint? {
        val state = if (found) when (pose) {
            "Open_Palm" -> HandHint.OPEN_PALM
            "Closed_Fist" -> HandHint.CLOSED_FIST
            else -> HandHint.HAND_SEEN
        } else HandHint.NONE
        if (found) {
            lastSeenAt = nowMs
            if (state == HandHint.OPEN_PALM || state == HandHint.CLOSED_FIST) {
                lastClassifiedAt = nowMs
            } else if ((lastHint == HandHint.OPEN_PALM ||
                    lastHint == HandHint.CLOSED_FIST) &&
                lastClassifiedAt != Long.MIN_VALUE &&
                nowMs >= lastClassifiedAt &&
                nowMs - lastClassifiedAt < weakClassificationGraceMs) {
                // Hold the last confirmed visual briefly through uncertain ML
                // frames. This is display-only and never feeds the action router.
                return null
            }
        }
        val current = if (found) state
        else if (lastSeenAt != Long.MIN_VALUE && nowMs - lastSeenAt < lostGraceMs)
            return null
        else HandHint.NONE
        if (current == lastHint &&
            (current == HandHint.NONE ||
                (lastEmittedAt != Long.MIN_VALUE && nowMs - lastEmittedAt < heartbeatMs))) {
            return null
        }
        lastHint = current
        lastEmittedAt = nowMs
        return current
    }

    fun reset() {
        lastSeenAt = Long.MIN_VALUE
        lastEmittedAt = Long.MIN_VALUE
        lastHint = HandHint.NONE
        lastClassifiedAt = Long.MIN_VALUE
    }
}