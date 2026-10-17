package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class ChromeShareCardPolicyTest {
    private val width = 709f
    private val height = 1536f
    private val controls = listOf(
        ChromeCardControl("Copy", 571f, 216f),
        ChromeCardControl("Edit", 654f, 216f)
    )

    @Test fun screenshotCardFindsUnlabeledShareBesideCopyAndEdit() {
        val p = ChromeShareCardPolicy.inferSharePoint(
            "com.android.chrome", true, width, height, controls)!!
        assertEquals(488f, p.x, .1f)
        assertEquals(216f, p.y, .1f)
    }

    @Test fun noCoordinatesFromUnrelatedAppsOrWithoutEditorAuthorization() {
        assertNull(ChromeShareCardPolicy.inferSharePoint(
            "com.google.android.googlequicksearchbox", true, width, height, controls))
        assertNull(ChromeShareCardPolicy.inferSharePoint(
            "com.android.chrome", false, width, height, controls))
        assertNull(ChromeShareCardPolicy.inferSharePoint(
            "com.android.chrome", true, width, height,
            listOf(ChromeCardControl("Copy", 571f, 216f))))
    }

    @Test fun refuseWrongLayoutHiddenAndAmbiguousButtons() {
        val badRows = listOf(
            listOf(ChromeCardControl("Copy", 571f, 1160f),
                ChromeCardControl("Edit", 654f, 1160f)),
            listOf(ChromeCardControl("Copy", 571f, 216f),
                ChromeCardControl("Edit", 654f, 400f)),
            listOf(ChromeCardControl("Copy", 571f, 216f),
                ChromeCardControl("Edit", 654f, 216f),
                ChromeCardControl("Edit", 654f, 216f)),
            listOf(ChromeCardControl("Copy", 571f, 216f, visible=false),
                ChromeCardControl("Edit", 654f, 216f)),
            listOf(ChromeCardControl("Copy", 571f, 216f),
                ChromeCardControl("Edit", 654f, 216f, clickable=false))
        )
        for (row in badRows) assertNull(ChromeShareCardPolicy.inferSharePoint(
            "com.android.chrome", true, width, height, row))
    }

    @Test fun supportsChineseCopyAndEditLabels() {
        val p = ChromeShareCardPolicy.inferSharePoint(
            "com.android.chrome", true, width, height,
            listOf(ChromeCardControl("複製", 571f, 217f),
                ChromeCardControl("編輯", 654f, 217f)))
        assertNotNull(p)
    }
    @Test fun secondRowChromeShareWithSamsungKeyboardVisible() {
        // S26 Ultra screenshot: page title/domain in row 2, icons Share/Copy/Edit
        // at roughly x=520/609/697, y=158 of a 755 x 1536 display.
        val p = ChromeShareCardPolicy.inferSharePoint(
            "com.android.chrome", true, 755f, 1536f,
            listOf(
                ChromeCardControl("複製", 609f, 158f),
                ChromeCardControl("編輯", 697f, 158f)
            ))!!
        assertEquals(521f, p.x, .1f)
        assertEquals(158f, p.y, .1f)
    }

    @Test fun chromeWindowUnderImeCanBeReadOnlyDuringOwnEditorFlow() {
        assertTrue(ChromeShareCardPolicy.mayUseChromeWindowBehindKeyboard(
            "com.android.chrome", "com.samsung.android.honeyboard", true, 1))
        assertTrue(ChromeShareCardPolicy.mayUseChromeWindowBehindKeyboard(
            "com.android.chrome", null, true, 1))
        assertFalse(ChromeShareCardPolicy.mayUseChromeWindowBehindKeyboard(
            "com.android.chrome", "com.android.chrome", true, 1))
        assertFalse(ChromeShareCardPolicy.mayUseChromeWindowBehindKeyboard(
            "com.android.chrome", "com.android.systemui", false, 1))
        assertFalse(ChromeShareCardPolicy.mayUseChromeWindowBehindKeyboard(
            "com.android.chrome", "com.samsung.android.honeyboard", true, 2))
        assertFalse(ChromeShareCardPolicy.mayUseChromeWindowBehindKeyboard(
            "com.google.android.googlequicksearchbox", "com.samsung.android.honeyboard", true, 1))
    }

}
