package tw.airgesture.transferbeta.core

import tw.airgesture.transferbeta.GestureSequence

/**
 * Routes one MediaPipe camera stream to either touch control or transfer.
 * A two-second fist wins over tapping. Short taps are sent on RELEASE only,
 * never during the two-second hold. No Android dependency: test on JVM.
 */
class FloatingControlRouter {
    enum class Command { SEND, RECEIVE, SWIPE_UP, SWIPE_DOWN, SWIPE_BACK, TAP, MEDIA_PLAY_PAUSE }
    data class Decision(
        val command: Command? = null,
        val pointer: PointerDwellEngine.Result? = null
    )

    private val fist = GestureSequence(holdMillis = 2_000L, unknownGraceMillis = 550L)
    private val swipes = GestureDecisionEngine(
        GestureDecisionEngine.Options(fastSwipeAssistEnabled = true)
    )
    private val pointer = PointerDwellEngine(dwellMs = 2_000L)
    private var palmFrames = 0
    private var lastPalm = -1L
    private var armed = false
    private var shortFistSince = -1L
    private var lastFist = -1L
    private var fistFrames = 0
    private var releasedFrames = 0
    private var consumed = false

    fun reset() {
        fist.reset()
        swipes.reset()
        pointer.reset()
        palmFrames = 0
        lastPalm = -1L
        armed = false
        shortFistSince = -1L
        lastFist = -1L
        fistFrames = 0
        releasedFrames = 0
        consumed = false
    }

    private fun clearShort() {
        shortFistSince = -1L
        lastFist = -1L
        fistFrames = 0
        releasedFrames = 0
        consumed = false
        palmFrames = 0
        armed = false
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
        youtubeForeground: Boolean
    ): Decision {
        val shape = if (confidence >= .65f) category else null
        val hold = fist.accept(
            if (shape == "Closed_Fist" || shape == "Open_Palm") shape else "",
            nowMs, receiveAvailable = receivingOffer
        )
        val transfer = when (hold) {
            GestureSequence.Result.SEND -> Command.SEND
            GestureSequence.Result.RECEIVE -> Command.RECEIVE
            else -> null
        }
        if (transfer != null) consumed = true

        val s = when (shape) {
            "Open_Palm" -> GestureDecisionEngine.Shape.OPEN_PALM
            "Closed_Fist" -> GestureDecisionEngine.Shape.CLOSED_FIST
            null -> GestureDecisionEngine.Shape.NO_HAND
            else -> GestureDecisionEngine.Shape.OTHER
        }
        val swipe = swipes.accept(GestureDecisionEngine.Frame(
            s, palmY, if (shape == null) 0f else confidence, palmX
        ), nowMs)
        val cursor = if (shape == "Pointing_Up" &&
            indexX != null && indexY != null) {
            pointer.accept(indexX, indexY, nowMs)
        } else {
            pointer.reset()
            null
        }

        var shortClick: Command? = null
        when (shape) {
            "Open_Palm" -> {
                if (shortFistSince >= 0L || consumed) {
                    releasedFrames++
                    if (releasedFrames >= 2) {
                        if (!consumed && shortFistSince >= 0L &&
                            fistFrames >= 2 && nowMs >= lastFist &&
                            nowMs - lastFist <= 650L &&
                            nowMs - shortFistSince < 2_000L) {
                            shortClick = if (youtubeForeground)
                                Command.MEDIA_PLAY_PAUSE else Command.TAP
                        }
                        clearShort()
                    }
                } else {
                    palmFrames = if (lastPalm >= 0L && nowMs - lastPalm <= 550L)
                        palmFrames + 1 else 1
                    if (palmFrames >= 2) armed = true
                }
                lastPalm = nowMs
            }
            "Closed_Fist" -> {
                palmFrames = 0
                releasedFrames = 0
                if (shortFistSince < 0L && armed && lastPalm >= 0L &&
                    nowMs >= lastPalm && nowMs - lastPalm <= 950L) {
                    shortFistSince = nowMs
                }
                if (shortFistSince >= 0L && !consumed) {
                    if (lastFist >= 0L && nowMs - lastFist > 550L) fistFrames = 0
                    fistFrames++
                    lastFist = nowMs
                }
            }
            else -> {
                if (lastFist >= 0L && nowMs - lastFist > 650L) clearShort()
                if (lastPalm >= 0L && nowMs - lastPalm > 650L) {
                    palmFrames = 0
                    armed = false
                }
            }
        }

        if (transfer != null) return Decision(transfer)
        if (shortClick != null) return Decision(shortClick)
        val swipeCommand = when (swipe) {
            GestureDecisionEngine.Action.SWIPE_UP -> Command.SWIPE_UP
            GestureDecisionEngine.Action.SWIPE_DOWN -> Command.SWIPE_DOWN
            GestureDecisionEngine.Action.SWIPE_BACK -> Command.SWIPE_BACK
            else -> null // Prevent original engine's early/double fist clicks.
        }
        if (swipeCommand != null) {
            armed = false
            shortFistSince = -1L
            return Decision(swipeCommand)
        }
        return Decision(pointer = cursor)
    }
}
