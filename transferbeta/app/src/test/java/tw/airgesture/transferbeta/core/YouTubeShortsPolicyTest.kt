package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class YouTubeShortsPolicyTest {
    @Test fun knownShortsPlayerIdsTriggerSingleScreenTap() {
        assertTrue(YouTubeShortsPolicy.isPlayerResource(
            "com.google.android.youtube:id/reel_watch_fragment"))
        assertTrue(YouTubeShortsPolicy.isPlayerResource(
            "com.google.android.youtube:id/shorts_player"))
        assertTrue(YouTubeShortsPolicy.isPlayerClass(
            "com.google.android.youtube.ReelWatchView"))
        assertTrue(YouTubeShortsPolicy.useScreenTap(
            YouTubeShortsPolicy.AUTO, true))
    }

    @Test fun shortsNavigationTabDoesNotMisidentifyNormalVideo() {
        assertFalse(YouTubeShortsPolicy.isPlayerResource(
            "com.google.android.youtube:id/shorts_tab"))
        assertFalse(YouTubeShortsPolicy.isPlayerResource(
            "com.google.android.youtube:id/bottom_navigation_shorts"))
        assertFalse(YouTubeShortsPolicy.isPlayerResource(
            "com.google.android.youtube:id/shorts"))
        assertFalse(YouTubeShortsPolicy.useScreenTap(
            YouTubeShortsPolicy.AUTO, false))
    }

    @Test fun fallbackModesWorkWhenYoutubeViewIsNotExposed() {
        assertTrue(YouTubeShortsPolicy.useScreenTap(
            YouTubeShortsPolicy.FORCE_SCREEN_TAP, false))
        assertFalse(YouTubeShortsPolicy.useScreenTap(
            YouTubeShortsPolicy.MEDIA_KEY_ONLY, true))
    }

    @Test fun tapCoordinatesAvoidRightSideVideoControlsAndCaption() {
        assertEquals(430f, YouTubeShortsPolicy.tapX(1000), .001f)
        assertEquals(660f, YouTubeShortsPolicy.tapY(2000), .001f)
    }
}
