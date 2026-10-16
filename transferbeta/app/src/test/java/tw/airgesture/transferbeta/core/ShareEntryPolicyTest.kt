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
    @Test fun explicitSingleGalleryPhotoIsNotRejectedByAuxiliaryClipGrants() {
        assertEquals("content://gallery/photo/123",
            ShareEntryPolicy.chooseSingleOriginal(
                "android.intent.action.SEND", "content://gallery/photo/123",
                listOf("content://gallery/photo/123", "content://gallery/thumbnail/456")))
    }
    @Test fun fallbackClipDataRequiresExactlyOneDistinctContentFile() {
        assertEquals("content://gallery/photo/123",
            ShareEntryPolicy.chooseSingleOriginal(
                "android.intent.action.SEND", null,
                listOf("content://gallery/photo/123", "content://gallery/photo/123")))
        assertNull(ShareEntryPolicy.chooseSingleOriginal(
            "android.intent.action.SEND", null,
            listOf("content://gallery/photo/123", "content://gallery/photo/999")))
        assertNull(ShareEntryPolicy.chooseSingleOriginal(
            "android.intent.action.SEND_MULTIPLE", "content://gallery/photo/123",
            emptyList()))
        assertNull(ShareEntryPolicy.chooseSingleOriginal(
            "android.intent.action.SEND", "file:///sdcard/DCIM/test.jpg", emptyList()))
    }
    @Test fun onlySingleItemShareAccepted() {
        assertTrue(ShareEntryPolicy.validItemCount(0))
        assertTrue(ShareEntryPolicy.validItemCount(1))
        assertFalse(ShareEntryPolicy.validItemCount(2))
        assertFalse(ShareEntryPolicy.validItemCount(-1))
    }
}