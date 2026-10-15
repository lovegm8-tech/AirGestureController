package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class ShareAutomationPolicyTest {
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
