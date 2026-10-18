package tw.airgesture.transferbeta.core

/**
 * Shorts has a separate on-screen playback controller. Detect only known
 * PLAYER resources: the generic YouTube bottom navigation "Shorts" tab is
 * deliberately excluded to avoid changing normal video behavior.
 *
 * Internal YouTube resource IDs vary by version. Optional manual override
 * lets a tester choose the screen tap without background UI scanning.
 */
object YouTubeShortsPolicy {
    const val AUTO = 0
    const val FORCE_SCREEN_TAP = 1
    const val MEDIA_KEY_ONLY = 2

    fun isPlayerResource(viewId: String?): Boolean {
        if (viewId == null) return false
        val id = viewId.substringAfterLast('/').lowercase()
        return id.contains("reel_watch_fragment") ||
            id.contains("reel_recycler") ||
            id.contains("reel_player") ||
            id.contains("shorts_player") ||
            id.contains("shorts_container") ||
            id.contains("shorts_recycler") ||
            id.contains("reel_pager") ||
            id.contains("reel_video")
    }

    fun useScreenTap(mode: Int, shortsPlayerDetected: Boolean): Boolean = when (mode) {
        FORCE_SCREEN_TAP -> true
        MEDIA_KEY_ONLY -> false
        else -> shortsPlayerDetected
    }

    /** A clear area of a Shorts clip, away from right-side buttons and captions. */
    fun tapX(width: Int): Float = width * .43f
    fun tapY(height: Int): Float = height * .33f
}
