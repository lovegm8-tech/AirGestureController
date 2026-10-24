package tw.airgesture.transferbeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseNotesTest {
    @Test fun latestHistoryMustMatchInstalledApkVersion() {
        assertEquals(BuildConfig.VERSION_NAME, AppReleaseNotes.latestVersion)
        assertTrue(AppReleaseNotes.containsVersion(BuildConfig.VERSION_NAME))
        assertTrue(BuildConfig.VERSION_CODE >= 54)
    }

    @Test fun everyHistoricalVersionIsPresentInDescendingOrder() {
        val all = AppReleaseNotes.entries
        assertEquals(all.size, all.map { it.version }.distinct().size)
        val numbers = all.map {
            val match = Regex("^1\\.0\\.(\\d+)$").matchEntire(it.version)
                ?: throw AssertionError("Unexpected version ${it.version}")
            match.groupValues[1].toInt()
        }
        assertEquals((numbers.first() downTo 8).toList(), numbers)
        assertTrue(all.all { it.summary.length >= 8 })
    }

    @Test fun logShownInUiMustContainBothNfcAndNewAiChanges() {
        val uiLog = AppReleaseNotes.displayText()
        assertTrue(uiLog.startsWith("v" + BuildConfig.VERSION_NAME))
        assertTrue(uiLog.contains("v1.0.22"))
        assertTrue(uiLog.contains("v1.0.38"))
        assertTrue(uiLog.contains("Qwen"))
        assertTrue(uiLog.contains("NFC"))
        assertFalse(uiLog.contains("\\\\n"))
    }
}
