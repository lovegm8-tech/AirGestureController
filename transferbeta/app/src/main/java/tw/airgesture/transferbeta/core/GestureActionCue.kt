package tw.airgesture.transferbeta.core

/** One-shot visual acknowledgement of a gesture command, never a transfer result.
 * A cue is returned only when the user has completed an actionable gesture. */
data class GestureActionCue(val glyph: String, val label: String)

object GestureActionCuePolicy {
    fun forControl(command: FloatingControlRouter.Command): GestureActionCue? =
        when (command) {
            FloatingControlRouter.Command.SWIPE_UP -> GestureActionCue("↑", "向上滑")
            FloatingControlRouter.Command.SWIPE_DOWN -> GestureActionCue("↓", "向下滑")
            FloatingControlRouter.Command.SWIPE_BACK -> GestureActionCue("→", "返回上一頁")
            FloatingControlRouter.Command.SWIPE_LEFT -> GestureActionCue("←", "由右往左滑")
            FloatingControlRouter.Command.TAP -> GestureActionCue("●", "點擊")
            FloatingControlRouter.Command.MEDIA_PLAY_PAUSE ->
                GestureActionCue("⏯", "播放／暫停")
            // Sending and receiving have their own status animations, which
            // also distinguish 'capture started' from 'transfer succeeded'.
            FloatingControlRouter.Command.SEND,
            FloatingControlRouter.Command.RECEIVE -> null
        }

    fun forPointerDwell(): GestureActionCue = GestureActionCue("◎", "食指點擊")
}
