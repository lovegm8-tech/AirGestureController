package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class ShareAutomationPolicyTest {
    @Test fun samsungTwoModeSubmenuSelectsOnlyOrdinaryAirGrab() {
        // Real v1.0.16 regression: choosing our app icon exposes a second
        // sheet with ordinary and NFC activities from the same package.
        val options = listOf(
            ActionNode(null, "懸浮操作", clickable = true),
            ActionNode(null, "懸浮操作 · NFC 碰一碰", clickable = true)
        )
        assertEquals(0, ShareAutomationPolicy.onlyRegularAirGrabSubtarget(
            "com.samsung.android.app.sharelive", options, "com.sec.android.gallery3d"))
        assertEquals(0, ShareAutomationPolicy.onlyRegularAirGrabSubtarget(
            "android", options, "com.sec.android.gallery3d"))
        assertEquals(0, ShareAutomationPolicy.onlyRegularAirGrabSubtarget(
            "com.sec.android.gallery3d", options, "com.sec.android.gallery3d"))
        assertEquals(0, ShareAutomationPolicy.onlyRegularAirGrabSubtarget(
            "com.samsung.android.app.sharelive", options, "com.zhiliaoapp.musically"))
        assertNull(ShareAutomationPolicy.onlyRegularAirGrabSubtarget(
            "com.malicious.app", options, "com.sec.android.gallery3d"))
        assertNull(ShareAutomationPolicy.onlyRegularAirGrabSubtarget(
            "android", options, "unknown.app"))
    }

    @Test fun samsungSubmenuRefusesAmbiguousOrMissingLabels() {
        val ordinary = ActionNode(null, "懸浮操作", clickable = true)
        val nfc = ActionNode(null, "懸浮操作 · NFC 碰一碰", clickable = true)
        assertNull(ShareAutomationPolicy.onlyRegularAirGrabSubtarget("android",
            listOf(ordinary, ordinary, nfc), "com.sec.android.gallery3d"))
        assertNull(ShareAutomationPolicy.onlyRegularAirGrabSubtarget("android",
            listOf(ordinary), "com.sec.android.gallery3d"))
        assertNull(ShareAutomationPolicy.onlyRegularAirGrabSubtarget("android",
            listOf(ordinary, nfc.copy(clickable = false)), "com.sec.android.gallery3d"))
        assertNull(ShareAutomationPolicy.onlyRegularAirGrabSubtarget("android",
            listOf(ordinary, nfc.copy(visible = false)), "com.sec.android.gallery3d"))
        assertNull(ShareAutomationPolicy.onlyRegularAirGrabSubtarget("android",
            listOf(ActionNode(null, "懸浮操作", clickable = true,
                texts = listOf("懸浮操作 · NFC 碰一碰")), nfc),
            "com.sec.android.gallery3d"))
    }

    @Test fun chooserCanMatchChildLabelWhenClickableContainerHasNoText() {
        val nodes = listOf(ActionNode(
            id = "android:id/resolver_target", label = null, clickable = true,
            texts = listOf("分享至", "AirGrab 隔空抓取", "應用程式"),
            ids = listOf("android:id/text1")))
        assertEquals(0, ShareAutomationPolicy.onlyUniqueChooserTarget("android", nodes))
    }
    @Test fun chooserNeverPicksAmbiguousOrUntrustedChildTargets() {
        val card = ActionNode(null, null, clickable = true, texts = listOf("AirGrab"))
        assertNull(ShareAutomationPolicy.onlyUniqueChooserTarget("android", listOf(card, card)))
        assertNull(ShareAutomationPolicy.onlyUniqueChooserTarget("com.sec.android.gallery3d",
            listOf(card)))
    }
    @Test fun shareButtonMatchesDescendantViewId() {
        val step = ShareStep(listOf("分享"), listOf(":id/action_share"))
        assertEquals(0, ShareAutomationPolicy.match(step, listOf(
            ActionNode(null, null, clickable = true,
                ids = listOf("com.sec.android.gallery3d:id/action_share"),
                texts = listOf("分享")))))
    }
    @Test fun focusedBrowserEditorCanYieldFullUrlWithoutGuessing() {
        val editor = VisibleText(null, "https://example.com/a/b?x=1", focused = true,
            editable = true)
        assertFalse(GrabContentPolicy.decide("com.android.chrome", listOf(editor)) is GrabChoice.Link)
        val found = GrabContentPolicy.decide("com.android.chrome", listOf(editor),
            allowFocusedEditor = true)
        assertEquals("https://example.com/a/b?x=1", (found as GrabChoice.Link).url)
    }
    @Test fun browserDoesNotReadRandomUnfocusedPageLink() {
        val page = VisibleText(null, "https://example.com", focused = false, editable = false)
        assertFalse(GrabContentPolicy.decide("com.android.chrome", listOf(page),
            allowFocusedEditor = true) is GrabChoice.Link)
    }
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
    @Test fun unifiedAppBrandIsRecognizedInTrustedChooserAndChromeHub() {
        val item = ActionNode(null, "懸浮操作", clickable = true)
        assertEquals(0, ShareAutomationPolicy.onlyUniqueChooserTarget("android", listOf(item)))
        assertEquals(0, ShareAutomationPolicy.onlyUniqueChooserTarget("com.samsung.android.app.sharelive", listOf(item)))
        assertEquals(0, ShareAutomationPolicy.browserHubTarget(listOf(item)))
        assertNull(ShareAutomationPolicy.onlyUniqueChooserTarget("com.sec.android.gallery3d", listOf(item)))
        assertNull(ShareAutomationPolicy.onlyUniqueChooserTarget("android", listOf(item, item)))
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
    @Test fun chromeEllipsisShareMenuAndGoogleShareLinkAreSupported() {
        val chrome = ShareAutomationPolicy.plan("com.android.chrome")!!
        assertEquals(0, ShareAutomationPolicy.match(chrome.steps.first(), listOf(
            ActionNode(null, "Share…", clickable = true),
            ActionNode(null, "Settings", clickable = true))))
        assertEquals(0, ShareAutomationPolicy.match(chrome.steps.first(), listOf(
            ActionNode(null, "分享...", clickable = true))))
        assertEquals(0, ShareAutomationPolicy.match(chrome.steps.first(), listOf(
            ActionNode(null, "分享網頁", clickable = true))))
        val google = ShareAutomationPolicy.plan("com.google.android.googlequicksearchbox")!!
        assertEquals(0, ShareAutomationPolicy.match(google.steps.first(), listOf(
            ActionNode(null, "Share link", clickable = true),
            ActionNode(null, "Open in browser", clickable = true))))
        assertEquals(0, ShareAutomationPolicy.match(google.steps.first(), listOf(
            ActionNode("com.google.android.googlequicksearchbox:id/share_button", null,
                clickable = true))))
    }
    @Test fun browserShareNeverAutoclicksAmbiguousTargets() {
        val step = ShareAutomationPolicy.plan("com.android.chrome")!!.steps.first()
        assertNull(ShareAutomationPolicy.match(step, listOf(
            ActionNode(null, "Share…", clickable = true),
            ActionNode(null, "Share link", clickable = true))))
        assertNull(ShareAutomationPolicy.match(step, listOf(
            ActionNode(null, "Share…", clickable = false))))
    }
    @Test fun chromeSharingHubRequiresRecognizedVisibleState() {
        val open = listOf("List of sharing options opened at half height.")
        assertTrue(ShareAutomationPolicy.isChromeSharingHub("com.android.chrome", open))
        assertFalse(ShareAutomationPolicy.isChromeSharingHub("com.android.chrome",
            listOf("List of sharing options is closed.")))
        assertFalse(ShareAutomationPolicy.isChromeSharingHub("com.google.android.googlequicksearchbox", open))
        assertFalse(ShareAutomationPolicy.isChromeSharingHub("com.android.chrome",
            listOf("More", "AirGrab")))
        val target = ActionNode(null, null, clickable = true, texts = listOf("AirGrab"))
        assertEquals(0, ShareAutomationPolicy.browserHubTarget(listOf(target)))
        assertNull(ShareAutomationPolicy.browserHubTarget(listOf(target, target)))
        assertEquals(0, ShareAutomationPolicy.browserHubMore(listOf(
            ActionNode(null, "More", clickable = true))))
        assertNull(ShareAutomationPolicy.browserHubMore(listOf(
            ActionNode(null, "More", clickable = true),
            ActionNode(null, "更多", clickable = true))))
    }

    @Test fun currentPageShareCardForChromeAndGoogle() {
        val cardShare = listOf(
            ActionNode(null, "Stay in touch", clickable = false),
            ActionNode("com.android.chrome:id/share_button", "分享", clickable = true),
            ActionNode(null, "複製", clickable = true),
            ActionNode(null, "編輯", clickable = true)
        )
        assertEquals(1, ShareAutomationPolicy.addressCardShare("com.android.chrome", cardShare))
        val googleShare = listOf(
            ActionNode(null, "分享連結", clickable = true),
            ActionNode(null, "複製", clickable = true)
        )
        assertEquals(0, ShareAutomationPolicy.addressCardShare(
            "com.google.android.googlequicksearchbox", googleShare))
        assertNull(ShareAutomationPolicy.addressCardShare(
            "com.sec.android.app.sbrowser", cardShare))
    }

    @Test fun currentPageShareMustBeUniqueAndVisible() {
        assertNull(ShareAutomationPolicy.addressCardShare("com.android.chrome", listOf(
            ActionNode(null, "分享", clickable = true),
            ActionNode(null, "Share", clickable = true))))
        assertNull(ShareAutomationPolicy.addressCardShare("com.android.chrome", listOf(
            ActionNode(null, "分享", clickable = true, visible = false))))
        assertNull(ShareAutomationPolicy.addressCardShare("com.android.chrome", listOf(
            ActionNode(null, "分享", clickable = true, password = true))))
        assertNull(ShareAutomationPolicy.addressCardShare("com.android.chrome", listOf(
            ActionNode(null, "最近的搜尋", clickable = true))))
    }

    @Test fun samsungChooserLineBreaksInAirGrabLabel() {
        assertEquals(0, ShareAutomationPolicy.onlyUniqueChooserTarget(
            "com.samsung.android.app.sharelive", listOf(
                ActionNode(null, "AirGrab 隔空抓取".replace(" ", System.lineSeparator()), clickable = true))))
        assertNull(ShareAutomationPolicy.onlyUniqueChooserTarget(
            "com.google.android.youtube", listOf(
                ActionNode(null, "AirGrab 隔空抓取", clickable = true))))
    }

}
