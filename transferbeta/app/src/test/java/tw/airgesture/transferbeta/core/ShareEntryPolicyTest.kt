package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class ShareEntryPolicyTest {
    @Test fun sendIntentUsesInvisibleShareReceiver() {
        assertTrue(ShareEntryPolicy.shouldHandleSilently("android.intent.action.SEND"))
        assertFalse(ShareEntryPolicy.shouldHandleSilently("android.intent.action.MAIN"))
    }
    @Test fun onlyContentSchemeAcceptedForSharedOriginalFiles() {
        assertTrue(ShareEntryPolicy.allowedFileScheme("content"))
        assertFalse(ShareEntryPolicy.allowedFileScheme("file"))
        assertFalse(ShareEntryPolicy.allowedFileScheme("https"))
        assertFalse(ShareEntryPolicy.allowedFileScheme(null))
    }
    @Test fun onlySingleItemShareAccepted() {
        assertTrue(ShareEntryPolicy.validItemCount(0))
        assertTrue(ShareEntryPolicy.validItemCount(1))
        assertFalse(ShareEntryPolicy.validItemCount(2))
        assertFalse(ShareEntryPolicy.validItemCount(-1))
    }
}