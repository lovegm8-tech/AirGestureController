package tw.airgesture.transferbeta.core

/** Re-check the actual foreground app when executing a gesture.
 * Cached window event packages can be stale after system/share overlays change.
 * In YouTube, the short palm-fist-palm gesture is media control, not a tap. */
object ControlTargetPolicy {
    private const val YOUTUBE = "com.google.android.youtube"

    fun commandForForeground(
        command: FloatingControlRouter.Command,
        actualForegroundPackage: String?
    ): FloatingControlRouter.Command =
        if (command == FloatingControlRouter.Command.TAP &&
            actualForegroundPackage == YOUTUBE
        ) FloatingControlRouter.Command.MEDIA_PLAY_PAUSE
        else command
}
