package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class YouTubeLinkPolicyTest {
    @Test fun screenshotConfirmedShareSheetCanChooseCopyLink() {
        val nodes = listOf(
            ActionNode(null, "複製連結", clickable = true),
            ActionNode(null, "快速分享", clickable = true),
            ActionNode(null, "建立貼文", clickable = true)
        )
        assertEquals(0, YouTubeLinkPolicy.copyLinkTarget(
            "com.google.android.youtube", nodes,
            listOf("分享", "複製連結", "快速分享", "建立貼文")))
        assertEquals(0, YouTubeLinkPolicy.copyLinkTarget(
            "com.google.android.youtube",
            listOf(ActionNode(null, "Copy link", clickable = true)),
            listOf("Quick Share")))
    }

    @Test fun noCopyOutsideProvenYouTubeShareSheet() {
        val copy = listOf(ActionNode(null, "複製連結", clickable = true))
        assertNull(YouTubeLinkPolicy.copyLinkTarget(
            "com.google.android.youtube", copy, listOf("影片內容")))
        assertNull(YouTubeLinkPolicy.copyLinkTarget(
            "com.android.chrome", copy, listOf("快速分享")))
        assertNull(YouTubeLinkPolicy.copyLinkTarget(
            "com.google.android.youtube", listOf(
                ActionNode(null, "複製連結", clickable = true),
                ActionNode(null, "複製連結", clickable = true)), listOf("快速分享")))
        assertNull(YouTubeLinkPolicy.copyLinkTarget(
            "com.google.android.youtube", listOf(
                ActionNode(null, "複製連結", clickable = false)), listOf("快速分享")))
    }

    @Test fun onlyNewVideoLinkIsAccepted() {
        val url = "https://youtu.be/abcdefghijk?si=123"
        assertEquals(url, YouTubeLinkPolicy.freshVideoLink(url, 100_000, 100_300, 101_000))
        assertNull(YouTubeLinkPolicy.freshVideoLink(url, 100_000, 70_000, 101_000))
        assertNull(YouTubeLinkPolicy.freshVideoLink(url, 100_000, 0, 101_000))
        assertNull(YouTubeLinkPolicy.freshVideoLink(url, 100_000, 100_300, 120_000))
        assertNull(YouTubeLinkPolicy.freshVideoLink(url, 100_000, 100_300, 99_000))
    }

    @Test fun nonVideoClipboardContentMustNotBeTransmitted() {
        val since = 100_000L
        val clipped = 100_500L
        val now = 101_000L
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk",
            YouTubeLinkPolicy.freshVideoLink("https://www.youtube.com/watch?v=abcdefghijk",
                since, clipped, now))
        assertEquals("https://youtube.com/shorts/abcdefghijk",
            YouTubeLinkPolicy.freshVideoLink("https://youtube.com/shorts/abcdefghijk",
                since, clipped, now))
        assertNull(YouTubeLinkPolicy.freshVideoLink("https://example.com/watch?v=abc",
            since, clipped, now))
        assertNull(YouTubeLinkPolicy.freshVideoLink("https://www.youtube.com/",
            since, clipped, now))
        assertNull(YouTubeLinkPolicy.freshVideoLink("https://www.youtube.com/watch",
            since, clipped, now))
        assertNull(YouTubeLinkPolicy.freshVideoLink("secret password",
            since, clipped, now))
        assertNull(YouTubeLinkPolicy.freshVideoLink(
            "https://youtu.be/abcdefghijk https://example.org/", since, clipped, now))
    }
}
