package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class SamsungGalleryScreenshotSharePolicyTest {
    private val gallery = SamsungGalleryScreenshotSharePolicy.GALLERY_PACKAGE
    private val modal = listOf("螢幕截圖分享形式：", "圖像", "網頁連結", "取消")

    @Test fun imageChoiceFromScreenshotWithEmbeddedUrl() {
        val items = listOf(
            ActionNode(null, "圖像", clickable = true),
            ActionNode(null, "網頁連結", clickable = true),
            ActionNode(null, "取消", clickable = true)
        )
        assertTrue(SamsungGalleryScreenshotSharePolicy.isChoiceDialog(
            gallery, gallery, modal))
        assertEquals(0, SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, gallery, modal, items))
        assertEquals(0, SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, "android", modal, items))
        assertEquals(0, SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, "com.samsung.android.app.sharelive", modal, items))
    }

    @Test fun shareRegularPhotosWithoutUnneededSecondClick() {
        val items = listOf(ActionNode(null, "圖像", clickable = true))
        assertFalse(SamsungGalleryScreenshotSharePolicy.isChoiceDialog(
            gallery, gallery, listOf("照片", "分享", "圖像")))
        assertNull(SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, gallery, listOf("照片", "分享", "圖像"), items))
    }

    @Test fun doNotClickImageForWebBrowserOrUntrustedApp() {
        val items = listOf(ActionNode(null, "圖像", clickable = true))
        assertNull(SamsungGalleryScreenshotSharePolicy.imageTarget(
            "com.android.chrome", "android", modal, items))
        assertNull(SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, "com.fake.dialog", modal, items))
        assertNull(SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, gallery, listOf("圖像", "網頁連結"), items))
        assertNull(SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, gallery, listOf("螢幕截圖分享形式：", "圖像"), items))
    }

    @Test fun rejectAmbiguousHiddenAndPasswordOptions() {
        assertNull(SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, gallery, modal, listOf(
                ActionNode(null, "圖像", clickable = true),
                ActionNode(null, "Image", clickable = true))))
        assertNull(SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, gallery, modal, listOf(
                ActionNode(null, "圖像", clickable = false))))
        assertNull(SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, gallery, modal, listOf(
                ActionNode(null, "圖像", clickable = true, visible = false))))
        assertNull(SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, gallery, modal, listOf(
                ActionNode(null, "圖像", clickable = true, password = true))))
    }

    @Test fun englishScreenshotSharingDialogAlsoUsesImage() {
        assertEquals(0, SamsungGalleryScreenshotSharePolicy.imageTarget(
            gallery, gallery, listOf("Screenshot sharing format:", "Image", "Webpage link"),
            listOf(
                ActionNode(null, "Image", clickable = true),
                ActionNode(null, "Webpage link", clickable = true))))
    }
}
