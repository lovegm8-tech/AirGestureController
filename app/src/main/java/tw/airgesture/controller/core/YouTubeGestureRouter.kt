package tw.airgesture.controller.core

/**
 * Converts the existing palm-to-fist click into distinct short/long actions
 * only when the official YouTube app is actually foreground.
 *
 * The first normal TAP_CENTER is *withheld* in YouTube, so a long fist never
 * taps the video before issuing the play/pause media key. Other apps receive
 * their original v0.2.7 actions completely unchanged.
 *
 * The long hold duration starts after the original engine's first confirmed
 * fist click, matching the time selector introduced in v0.2.7.
 */
class YouTubeGestureRouter(
    private val longHoldMs: Long = 700L,
    private val maximumFrameGapMs: Long = 550L,
    private val releaseConfirmationMs: Long = 130L
) {
    private var pending = false
    private var finishedLongFist = false
    private var fistStartMs = Long.MIN_VALUE
    private var lastFistMs = Long.MIN_VALUE
    private var nonFistStartMs = Long.MIN_VALUE
    private var youtubePreviously = false

    fun reset() {
        pending = false
        finishedLongFist = false
        fistStartMs = Long.MIN_VALUE
        lastFistMs = Long.MIN_VALUE
        nonFistStartMs = Long.MIN_VALUE
        youtubePreviously = false
    }

    fun route(
        shape: GestureDecisionEngine.Shape,
        nowMs: Long,
        action: GestureDecisionEngine.Action?,
        youtubeForeground: Boolean
    ): GestureDecisionEngine.Action? {
        if (!youtubeForeground) {
            // Never let a YouTube click leak into a newly foreground app.
            reset()
            return action
        }
        if (!youtubePreviously) {
            reset()
            youtubePreviously = true
        }

        if (finishedLongFist) {
            // Ignore stale v0.2.7 long-fist repeat while a fist is still held.
            if (shape != GestureDecisionEngine.Shape.CLOSED_FIST) {
                finishedLongFist = false
            }
            return if (action == GestureDecisionEngine.Action.TAP_CENTER) null else action
        }

        if (pending) {
            when (shape) {
                GestureDecisionEngine.Shape.CLOSED_FIST -> {
                    val gap = nowMs - lastFistMs
                    if (gap < 0 || gap > maximumFrameGapMs) {
                        reset()
                        youtubePreviously = true
                        return null
                    }
                    lastFistMs = nowMs
                    nonFistStartMs = Long.MIN_VALUE
                    if (nowMs - fistStartMs >= longHoldMs) {
                        pending = false
                        finishedLongFist = true
                        return GestureDecisionEngine.Action.MEDIA_PLAY_PAUSE
                    }
                }
                GestureDecisionEngine.Shape.OPEN_PALM,
                GestureDecisionEngine.Shape.OTHER -> {
                    if (nonFistStartMs == Long.MIN_VALUE) nonFistStartMs = nowMs
                    if (nowMs - nonFistStartMs >= releaseConfirmationMs) {
                        pending = false
                        return GestureDecisionEngine.Action.TAP_CENTER
                    }
                }
                GestureDecisionEngine.Shape.NO_HAND -> {
                    // Brief model flicker does not count as a released fist.
                    if (nowMs - lastFistMs >= maximumFrameGapMs) {
                        pending = false
                        return GestureDecisionEngine.Action.TAP_CENTER
                    }
                }
            }
            return null
        }

        if (action == GestureDecisionEngine.Action.TAP_CENTER &&
            shape == GestureDecisionEngine.Shape.CLOSED_FIST
        ) {
            pending = true
            fistStartMs = nowMs
            lastFistMs = nowMs
            nonFistStartMs = Long.MIN_VALUE
            return null
        }
        // Swipes and pointer actions are unchanged in YouTube.
        return action
    }
}
