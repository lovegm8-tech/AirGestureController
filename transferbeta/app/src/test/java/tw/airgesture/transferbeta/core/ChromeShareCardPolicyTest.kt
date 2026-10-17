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
}
