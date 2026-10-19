package tw.airgesture.transferbeta.core

/**
 * One MediaPipe stream controls gestures and file transfer.
 * - Palm -> fist (two confirmed observations each): send, once
 * - Direct fist held 1.5 s: tap, or YouTube media play/pause
 * - Incoming offer: fist -> palm: receive, never tap/send
 * The gesture controller does not raise AI/camera FPS.
 */
class FloatingControlRouter(pointerDwellMs: Long = 2000L, sensitivity: Int = 1) {
    enum class Command { SEND, RECEIVE, SWIPE_UP, SWIPE_DOWN, SWIPE_BACK, SWIPE_LEFT, TAP, MEDIA_PLAY_PAUSE }
    data class Decision(
        val command: Command? = null,
        val pointer: PointerDwellEngine.Result? = null
    )

    private val swipes = GestureDecisionEngine(
        GestureDecisionEngine.Options(fastSwipeAssistEnabled = true,
            minimumConfidence = .58f,
            swipeDistance = when (sensitivity) { 0 -> .18f; 2 -> .09f; else -> .13f })
    )
    private val pointer = PointerDwellEngine(dwellMs = pointerDwellMs)

    private var palmSamples = 0
    private var lastPalmAt = -1L
    private var palmArmed = false
    private var fistSince = -1L
    private var lastFistAt = -1L
    private var fistSamples = 0
    private var grabCandidate = false
    private var fistConsumed = false
    private var releasePalmSamples = 0
    private var otherSamples = 0

    fun reset() {
        swipes.reset()
        pointer.reset()
        palmSamples = 0
        lastPalmAt = -1L
        palmArmed = false
        fistSince = -1L
        lastFistAt = -1L
        fistSamples = 0
        grabCandidate = false
        fistConsumed = false
        releasePalmSamples = 0
        otherSamples = 0
    }

    private fun clearFist() {
        fistSince = -1L
        lastFistAt = -1L
        fistSamples = 0
        grabCandidate = false
        fistConsumed = false
        releasePalmSamples = 0
    }

    fun accept(
        category: String?,
        confidence: Float,
        palmX: Float?,
        palmY: Float?,
        indexX: Float?,
        indexY: Float?,
        nowMs: Long,
        receivingOffer: Boolean,
        youtubeForeground: Boolean,
        rawCategory: String? = category,
        palmFacingForSwipe: Boolean = true
    ): Decision {
        val shape = if (confidence >= .58f) category else null
        // Preserve unclassified-but-localized motion AFTER a genuine open palm.
        // Never reinterpret an identified fist, pointer, or other pose as motion.
        val validHandPosition = palmX != null && palmY != null &&
            palmX.isFinite() && palmY.isFinite() &&
            palmX in 0f..1f && palmY in 0f..1f
        val mayTrackUncertainHand = validHandPosition &&
            (rawCategory == null || rawCategory.isBlank() ||
                rawCategory == "None" || rawCategory == "Open_Palm")
        val gestureShape = when (shape) {
            "Open_Palm" -> GestureDecisionEngine.Shape.OPEN_PALM
            "Closed_Fist" -> GestureDecisionEngine.Shape.CLOSED_FIST
            null, "None" -> when {
                mayTrackUncertainHand -> GestureDecisionEngine.Shape.TRACKED_HAND
                // Even a weakly predicted fist or pointing gesture ends swipe
                // tracking; otherwise a later None frame could reuse its origin.
                validHandPosition && rawCategory != null &&
                    rawCategory.isNotBlank() && rawCategory != "None" &&
                    rawCategory != "Open_Palm" -> GestureDecisionEngine.Shape.OTHER
                else -> GestureDecisionEngine.Shape.NO_HAND
            }
            else -> GestureDecisionEngine.Shape.OTHER
        }
        val pointerResult = if (shape == "Pointing_Up" && confidence >= .65f &&
            indexX != null && indexY != null) {
            pointer.accept(indexX, indexY, nowMs)
        } else {
            pointer.reset()
            null
        }

        var action: Command? = null
        when (shape) {
            "Open_Palm" -> {
                otherSamples = 0
                // Two palm readings prevent a single noisy frame from releasing
                // the fist or arming a file grab.
                palmSamples = if (lastPalmAt >= 0L &&
                    nowMs >= lastPalmAt && nowMs - lastPalmAt <= 650L) palmSamples + 1 else 1
                lastPalmAt = nowMs
                if (fistSince >= 0L) {
                    releasePalmSamples++
                    if (releasePalmSamples >= 2) {
                        if (!fistConsumed && receivingOffer && fistSamples >= 2 &&
                            nowMs >= lastFistAt && nowMs - lastFistAt <= 1100L) {
                            action = Command.RECEIVE
                        }
                        clearFist()
                        // The palm immediately after a fist is a RELEASE,
                        // not a new file-grab preparation.
                        palmArmed = false
                        palmSamples = 0
                        swipes.reset()
                    }
                } else if (palmSamples >= 2) {
                    palmArmed = true
                }
            }
            "Closed_Fist" -> {
                otherSamples = 0
                releasePalmSamples = 0
                if (fistSince < 0L) {
                    fistSince = nowMs
                    fistSamples = 0
                    grabCandidate = palmArmed && lastPalmAt >= 0L &&
                        nowMs >= lastPalmAt && nowMs - lastPalmAt <= 950L
                    palmArmed = false
                } else if (lastFistAt < 0L || nowMs < lastFistAt ||
                    nowMs - lastFistAt > 900L) {
                    // A genuine tracking interruption starts the dwell again;
                    // a previously-fired fist remains latched until released.
                    fistSince = nowMs
                    fistSamples = 0
                    if (!fistConsumed) grabCandidate = false
                }
                fistSamples++
                lastFistAt = nowMs
                palmSamples = 0
                if (!fistConsumed && fistSamples >= 2) {
                    if (grabCandidate && !receivingOffer) {
                        // Explicit palm -> fist has priority even if held >1.5 s.
                        action = Command.SEND
                        fistConsumed = true
                    } else if (!receivingOffer && !grabCandidate &&
                        nowMs >= fistSince && nowMs - fistSince >= 1_500L) {
                        action = if (youtubeForeground) Command.MEDIA_PLAY_PAUSE else Command.TAP
                        fistConsumed = true
                    }
                }
            }
            else -> {
                // Tracking blips must not turn into a fresh palm -> fist.
                if (lastPalmAt >= 0L && (nowMs < lastPalmAt ||
                        nowMs - lastPalmAt > 950L)) {
                    palmSamples = 0
                    palmArmed = false
                }
                releasePalmSamples = 0
                otherSamples++
                if (otherSamples >= 2 && fistSince >= 0L &&
                    lastFistAt >= 0L && nowMs >= lastFistAt &&
                    nowMs - lastFistAt >= 850L) clearFist()
            }
        }

        // An incoming offer blocks tapping and grabbing while awaiting its
        // explicit fist -> palm confirmation.
        val swipeAction = if (action == null && fistSince < 0L &&
            !receivingOffer) swipes.accept(GestureDecisionEngine.Frame(
            gestureShape, palmY, when (gestureShape) {
                // Raw hand category has been rejected as a swipe endpoint.
                // Keep OTHER authoritative even if the classifier score is
                // below the engine's generic confidence threshold.
                GestureDecisionEngine.Shape.TRACKED_HAND,
                GestureDecisionEngine.Shape.OTHER -> 1f
                GestureDecisionEngine.Shape.NO_HAND -> 0f
                else -> confidence
            }, palmX, palmFacingForSwipe
        ), nowMs) else {
            swipes.reset()
            null
        }
        if (action == null) action = when (swipeAction) {
            GestureDecisionEngine.Action.SWIPE_UP -> Command.SWIPE_UP
            GestureDecisionEngine.Action.SWIPE_DOWN -> Command.SWIPE_DOWN
            GestureDecisionEngine.Action.SWIPE_BACK -> Command.SWIPE_BACK
            GestureDecisionEngine.Action.SWIPE_LEFT -> Command.SWIPE_LEFT
            else -> null
        }
        if (action == Command.SWIPE_UP || action == Command.SWIPE_DOWN ||
            action == Command.SWIPE_BACK || action == Command.SWIPE_LEFT) {
            // Swiping an open palm must not arm a subsequent accidental grab.
            palmArmed = false
            palmSamples = 0
        }
        return Decision(command = action, pointer = pointerResult)
    }
}
