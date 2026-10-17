package tw.airgesture.controller.core

/**
 * The official YouTube app uses one dedicated gesture:
 *
 *       stable open palm -> confirmed closed fist -> MEDIA_PLAY_PAUSE
 *
 * The media key is emitted on the SECOND good fist frame (usually ~100-200 ms
 * after the fist is seen), never after an additional "long hold" timer.
 *
 * No central touch is ever substituted for an incomplete fist. A single fist
 * can emit at most one key until the hand has visibly reopened long enough to
 * re-arm the gesture. In all other apps legacy actions pass through unchanged.
 *
 * This is independent of the original GestureDecisionEngine's tap state, so
 * circular-volume arbitration and previous swipe cooldown cannot lose a media
 * play/pause action.
 */
class YouTubeGestureRouter(
    private val maxFrameGapMs: Long = 480L,
    private val palmToFistWindowMs: Long = 1200L,
    private val rearmPalmMs: Long = 320L,
    private val minTriggerGapMs: Long = 650L
) {
    private enum class Stage { SEEK_PALM, ARMED, FIRED }

    private var stage = Stage.SEEK_PALM
    private var youtubePreviously = false
    private var lastSeenAt = Long.MIN_VALUE
    private var lastPalmAt = Long.MIN_VALUE
    private var openFrames = 0
    private var fistFrames = 0
    private var lastFistAt = Long.MIN_VALUE
    private var rearmPalmSince = Long.MIN_VALUE
    private var triggeredAt = Long.MIN_VALUE

    fun reset() {
        stage = Stage.SEEK_PALM
        youtubePreviously = false
        lastSeenAt = Long.MIN_VALUE
        lastPalmAt = Long.MIN_VALUE
        openFrames = 0
        fistFrames = 0
        lastFistAt = Long.MIN_VALUE
        rearmPalmSince = Long.MIN_VALUE
        triggeredAt = Long.MIN_VALUE
    }

    private fun beginYoutube() {
        reset()
        youtubePreviously = true
    }

    fun route(
        shape: GestureDecisionEngine.Shape,
        nowMs: Long,
        action: GestureDecisionEngine.Action?,
        youtubeForeground: Boolean
    ): GestureDecisionEngine.Action? {
        if (!youtubeForeground) {
            reset()
            return action
        }
        if (!youtubePreviously) beginYoutube()

        // Suppress ALL v0.2.7 tap and long-fist clicks on YouTube. No accidental
        // touch is sent if the fist fails confirmation.
        val otherwise = if (action == GestureDecisionEngine.Action.TAP_CENTER)
            null else action

        if (lastSeenAt != Long.MIN_VALUE &&
            (nowMs < lastSeenAt || nowMs - lastSeenAt > 1600L)) {
            beginYoutube()
        }
        lastSeenAt = nowMs

        if (stage == Stage.FIRED) {
            // A motionless fist must NEVER toggle twice. Re-arming requires
            // multiple real OPEN_PALM observations spaced over >=320ms.
            if (shape == GestureDecisionEngine.Shape.OPEN_PALM) {
                if (rearmPalmSince == Long.MIN_VALUE) rearmPalmSince = nowMs
                if (nowMs - rearmPalmSince >= rearmPalmMs &&
                    triggeredAt != Long.MIN_VALUE &&
                    nowMs - triggeredAt >= minTriggerGapMs) {
                    stage = Stage.ARMED
                    lastPalmAt = nowMs
                    openFrames = 2
                    fistFrames = 0
                    lastFistAt = Long.MIN_VALUE
                }
            } else if (shape == GestureDecisionEngine.Shape.CLOSED_FIST) {
                rearmPalmSince = Long.MIN_VALUE
            } else if (rearmPalmSince != Long.MIN_VALUE &&
                nowMs - rearmPalmSince > maxFrameGapMs) {
                rearmPalmSince = Long.MIN_VALUE
            }
            return otherwise
        }

        if (shape == GestureDecisionEngine.Shape.OPEN_PALM) {
            if (lastPalmAt == Long.MIN_VALUE ||
                nowMs < lastPalmAt || nowMs - lastPalmAt > maxFrameGapMs) {
                openFrames = 0
            }
            lastPalmAt = nowMs
            openFrames++
            if (openFrames >= 2) stage = Stage.ARMED
            fistFrames = 0
            lastFistAt = Long.MIN_VALUE
            return otherwise
        }

        if (stage != Stage.ARMED) return otherwise

        // Once an open palm has been confirmed, an actual fist must appear
        // shortly afterwards. Brief model flicker is fine; a stale hand isn't.
        if (lastPalmAt == Long.MIN_VALUE ||
            nowMs < lastPalmAt || nowMs - lastPalmAt > palmToFistWindowMs) {
            stage = Stage.SEEK_PALM
            fistFrames = 0
            return otherwise
        }
        if (shape == GestureDecisionEngine.Shape.CLOSED_FIST) {
            if (lastFistAt == Long.MIN_VALUE ||
                nowMs < lastFistAt || nowMs - lastFistAt > maxFrameGapMs) {
                fistFrames = 0
            }
            lastFistAt = nowMs
            fistFrames++
            if (fistFrames >= 2) {
                stage = Stage.FIRED
                triggeredAt = nowMs
                rearmPalmSince = Long.MIN_VALUE
                return GestureDecisionEngine.Action.MEDIA_PLAY_PAUSE
            }
        } else if (shape == GestureDecisionEngine.Shape.OTHER) {
            // Bad pose isn't a verified fist. Preserve the preceding palm
            // briefly, but require two fresh fist frames after the blur.
            fistFrames = 0
            lastFistAt = Long.MIN_VALUE
        }
        // NO_HAND is allowed as a short interruption; timeout above still
        // prevents stale hands and repeated toggles.
        return otherwise
    }
}
