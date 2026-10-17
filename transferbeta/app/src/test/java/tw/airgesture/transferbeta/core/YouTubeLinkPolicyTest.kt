package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class YouTubeLinkPolicyTest {
    private val sheet = listOf("分享", "複製連結", "快速分享", "建立貼文")

    @Test fun youtubeNativeShareSheetOpensOtherApps() {
        val options = listOf(
            ActionNode(null, "LINE", clickable = true),
            ActionNode(null, "其他項目", clickable = true),
            ActionNode(null, "複製連結", clickable = true)
        )
        assertTrue(YouTubeLinkPolicy.isShareSheet("com.google.android.youtube", sheet))
        assertEquals(1, YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube", options, sheet))
        assertEquals(0, YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube",
            listOf(ActionNode(null, "Other apps", clickable = true)),
            listOf("Copy link", "Create post")))
        assertEquals(0, YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube",
            listOf(ActionNode(null, "其他项目", clickable = true)),
            listOf("快速分享")))
    }

    @Test fun neverClicksUnknownOrAmbiguousYouTubeControls() {
        val options = listOf(ActionNode(null, "其他項目", clickable = true))
        assertNull(YouTubeLinkPolicy.moreAppsTarget(
            "com.android.chrome", options, sheet))
        assertNull(YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube", options, listOf("影片內容")))
        assertNull(YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube",
            listOf(ActionNode(null, "其他項目", clickable = true),
                ActionNode(null, "其他項目", clickable = true)), sheet))
        assertNull(YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube",
            listOf(ActionNode(null, "其他項目", clickable = false)), sheet))
        assertNull(YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube",
            listOf(ActionNode(null, "其他項目", clickable = true, password = true)), sheet))
    }

    @Test fun youtubeShortsShareUsesSameAppChooserAsNormalVideo() {
        // Shorts puts Share in the right-hand action rail rather than below the
        // video, but both controls belong to the same YouTube package.
        val plan = ShareAutomationPolicy.plan("com.google.android.youtube")!!
        assertEquals(GrabKind.VIDEO, plan.kind)
        assertEquals(WantedResult.LINK, plan.expected)
        val shortsActions = listOf(
            ActionNode(null, "喜歡", clickable = true),
            ActionNode(null, "留言", clickable = true),
            ActionNode("com.google.android.youtube:id/share_button", "分享", clickable = true),
            ActionNode(null, "Remix", clickable = true)
        )
        assertEquals(2, ShareAutomationPolicy.match(plan.steps.first(), shortsActions))
        val shortsSheet = listOf("分享", "複製連結", "快速分享")
        assertTrue(YouTubeLinkPolicy.isShareSheet(
            "com.google.android.youtube", shortsSheet))
        assertEquals(1, YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube",
            listOf(ActionNode(null, "LINE", clickable = true),
                   ActionNode(null, "其他項目", clickable = true)),
            shortsSheet))
    }

    @Test fun shortsShareDoesNotMistakeCopyLinkForOtherApps() {
        val shortsSheet = listOf("分享", "複製連結", "快速分享")
        assertNull(YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube",
            listOf(ActionNode(null, "複製連結", clickable = true)),
            shortsSheet))
        assertNull(ShareAutomationPolicy.match(
            ShareAutomationPolicy.plan("com.google.android.youtube")!!.steps.first(),
            listOf(ActionNode(null, "分享", clickable = true),
                   ActionNode(null, "分享", clickable = true))))
    }

    @Test fun labelOnlyEllipsisIsNotEnoughToClick() {
        assertNull(YouTubeLinkPolicy.moreAppsTarget(
            "com.google.android.youtube",
            listOf(ActionNode(null, "•••", clickable = true)), sheet))
        assertFalse(YouTubeLinkPolicy.isShareSheet(
            "com.google.android.youtube", listOf("影片標題", "留言")))
    }
}
