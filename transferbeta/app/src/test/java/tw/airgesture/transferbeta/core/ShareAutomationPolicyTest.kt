package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class ShareAutomationPolicyTest {
    @Test fun knownOverflowAllowsNestedBrowserShare() {
        val nodes = listOf(
            ActionNode("com.android.chrome:id/menu_button", null, clickable = true),
            ActionNode(null, "網址", clickable = true))
        assertEquals(0, ShareAutomationPolicy.browserOverflow(nodes))
        assertNull(ShareAutomationPolicy.browserOverflow(listOf(
            ActionNode(null, "不明選單", clickable = true))))
        assertEquals(0, ShareAutomationPolicy.browserOverflow(listOf(
            ActionNode(null, "Customize and control Google Chrome", clickable = true))))
    }
    @Test fun googleSearchAppHasBrowserSharePlan() {
        assertEquals(GrabKind.BROWSER,
            ShareAutomationPolicy.plan("com.google.android.googlequicksearchbox")!!.kind)
        assertTrue(GrabContentPolicy.isBrowser("com.google.android.googlequicksearchbox"))
    }
    @Test fun browserAddressControlCanReadUrl() {
        assertTrue(GrabContentPolicy.isAddressId("com.android.chrome:id/url_bar"))
        assertTrue(GrabContentPolicy.isAddressId("com.sec.android.app.sbrowser:id/location_bar"))
        assertFalse(GrabContentPolicy.isAddressId("com.android.chrome:id/search_results"))
        val result = GrabContentPolicy.decide("com.android.chrome", listOf(
            VisibleText("com.android.chrome:id/url_bar", "https://example.com/test?x=1")))
        assertEquals("https://example.com/test?x=1", (result as GrabChoice.Link).url)
    }
    @Test fun browserCanUseOfficialShareIfAddressBarNotReadable() {
        val plan=ShareAutomationPolicy.plan("com.android.chrome")!!
        assertEquals(GrabKind.BROWSER,plan.kind)
        assertEquals(WantedResult.LINK,plan.expected)
    }
    @Test fun officeUsesEditableCopy() {
        val p = ShareAutomationPolicy.plan("com.microsoft.office.excel")!!
        assertEquals(GrabKind.OFFICE, p.kind)
        assertEquals(WantedResult.FILE, p.expected)
    }
    @Test fun cloudDocumentPlansTwoSteps() {
        val p = ShareAutomationPolicy.plan("com.google.android.apps.docs.editors.sheets")!!
        assertEquals(2, p.steps.size)
    }
    @Test fun socialIsLinkOnly() {
        assertEquals(WantedResult.LINK,
            ShareAutomationPolicy.plan("com.instagram.android")!!.expected)
    }
    @Test fun unknownAppMustNotAutoclick() {
        assertNull(ShareAutomationPolicy.plan("unknown.app"))
    }
    @Test fun ambiguityMeansNoClick() {
        val step = ShareStep(listOf("share"))
        assertNull(ShareAutomationPolicy.match(step,
            listOf(ActionNode(null, "Share", clickable = true),
                ActionNode(null, "Share", clickable = true))))
        assertEquals(0, ShareAutomationPolicy.match(step,
            listOf(ActionNode(null, "Share", clickable = true),
                ActionNode(null, "Copy", clickable = true))))
    }
    @Test fun passwordOrHiddenCannotBeChosen() {
        val step=ShareStep(listOf("share"))
        assertNull(ShareAutomationPolicy.match(step,
            listOf(ActionNode(null, "Share", clickable = true, password = true))))
        assertNull(ShareAutomationPolicy.match(step,
            listOf(ActionNode(null, "Share", clickable = true, visible = false))))
    }
    @Test fun chooserRestrictedToTrustedResolver() {
        val nodes = listOf(ActionNode(null, "隔空傳檔測試版", clickable = true))
        assertNull(ShareAutomationPolicy.onlyUniqueChooserTarget("com.fake.app", nodes))
        assertEquals(0, ShareAutomationPolicy.onlyUniqueChooserTarget("android", nodes))
    }
    @Test fun brandedNameIsRecognizedOnSharesheet() {
        val nodes=listOf(ActionNode(null, "AirGrab 隔空抓取", clickable=true))
        assertEquals(0,ShareAutomationPolicy.onlyUniqueChooserTarget("android",nodes))
    }
    @Test fun samsungSharesheetCanExposeShortAppName() {
        val nodes = listOf(ActionNode(null, "AirGrab", clickable = true))
        assertEquals(0, ShareAutomationPolicy.onlyUniqueChooserTarget(
            "com.samsung.android.app.sharelive", nodes))
        assertNull(ShareAutomationPolicy.onlyUniqueChooserTarget(
            "com.sec.android.gallery3d", nodes))
    }
    @Test fun galleryShareEntryRecognized() {
        val plan = ShareAutomationPolicy.plan("com.sec.android.gallery3d")!!
        assertEquals(GrabKind.PHOTO, plan.kind)
        assertEquals(0, ShareAutomationPolicy.match(plan.steps.first(), listOf(
            ActionNode("com.sec.android.gallery3d:id/share", null, clickable = true),
            ActionNode(null, "編輯", clickable = true))))
    }
    @Test fun chooserAmbiguousMeansNoClick() {
        val n=ActionNode(null, "隔空傳檔測試版", clickable=true)
        assertNull(ShareAutomationPolicy.onlyUniqueChooserTarget("android", listOf(n,n)))
    }
    @Test fun menuCanMatchKnownShareIds() {
        val s = ShareStep(listOf("分享"),listOf(":id/share_button"))
        assertEquals(0,ShareAutomationPolicy.match(s,
            listOf(ActionNode("com.google.android.youtube:id/share_button", null, clickable=true))))
    }
}
