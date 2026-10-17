package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class TikTokSharePolicyTest {
    private val origin = "com.zhiliaoapp.musically"
    private val visible = listOf("傳送至", "轉發", "複製連結", "舉報")

    @Test fun tiktokSendToRecognizesMoreAtEndOfAppRow() {
        val row = listOf(
            TikTokShareCandidate(ActionNode(null, "Line", clickable = true), 0.80f),
            TikTokShareCandidate(ActionNode(null, "Email", clickable = true), 0.80f),
            TikTokShareCandidate(ActionNode(null, "更多", clickable = true), 0.80f)
        )
        assertTrue(TikTokSharePolicy.isNativeShareSheet(origin, visible))
        assertEquals(2, TikTokSharePolicy.moreAppsTarget(origin, visible, row))
        assertEquals(2, TikTokSharePolicy.moreAppsTarget(
            "com.ss.android.ugc.trill", listOf("Send to"), row))
    }

    @Test fun noMoreOutsideConfirmedNativePanelOrUnrelatedApps() {
        val candidate = listOf(
            TikTokShareCandidate(ActionNode(null, "更多", clickable = true), 0.80f))
        assertNull(TikTokSharePolicy.moreAppsTarget(origin, listOf("影片內容"), candidate))
        assertNull(TikTokSharePolicy.moreAppsTarget(
            "com.google.android.youtube", visible, candidate))
        assertNull(TikTokSharePolicy.moreAppsTarget(
            "com.instagram.android", visible, candidate))
        assertFalse(TikTokSharePolicy.isNativeShareSheet(
            "com.android.chrome", listOf("傳送至")))
    }

    @Test fun moreInVideoCaptionOrBottomActionsCannotBeTapped() {
        val bodyMore = listOf(
            TikTokShareCandidate(ActionNode(null, "更多", clickable = true), 0.91f))
        assertNull(TikTokSharePolicy.moreAppsTarget(origin, visible, bodyMore))
        val topMore = listOf(
            TikTokShareCandidate(ActionNode(null, "更多", clickable = true), 0.60f))
        assertNull(TikTokSharePolicy.moreAppsTarget(origin, visible, topMore))
        val duplicate = listOf(
            TikTokShareCandidate(ActionNode(null, "更多", clickable = true), 0.80f),
            TikTokShareCandidate(ActionNode(null, "more", clickable = true), 0.80f))
        assertNull(TikTokSharePolicy.moreAppsTarget(origin, visible, duplicate))
    }

    @Test fun hiddenOrUnsafeButtonsNeverGetClicked() {
        val invalid = listOf(
            TikTokShareCandidate(ActionNode(null, "更多", clickable = false), 0.80f),
            TikTokShareCandidate(ActionNode(null, "更多", clickable = true,
                password = true), 0.80f),
            TikTokShareCandidate(ActionNode(null, "更多", clickable = true,
                visible = false), 0.80f))
        assertNull(TikTokSharePolicy.moreAppsTarget(origin, visible, invalid))
    }

    @Test fun noConfusionWithCopyLinkOrForward() {
        val candidates = listOf(
            TikTokShareCandidate(ActionNode(null, "轉發", clickable = true), 0.80f),
            TikTokShareCandidate(ActionNode(null, "複製連結", clickable = true), 0.80f),
            TikTokShareCandidate(ActionNode(null, "Messenger", clickable = true), 0.80f))
        assertNull(TikTokSharePolicy.moreAppsTarget(origin, visible, candidates))
        assertEquals(WantedResult.LINK, ShareAutomationPolicy.plan(origin)!!.expected)
        assertEquals(GrabKind.SOCIAL, ShareAutomationPolicy.plan(origin)!!.kind)
    }
}
