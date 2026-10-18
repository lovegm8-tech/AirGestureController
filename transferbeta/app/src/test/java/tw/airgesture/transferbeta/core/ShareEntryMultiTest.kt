package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class ShareEntryMultiTest {
    private val multi = "android.intent.action.SEND_MULTIPLE"
    private val one = "android.intent.action.SEND"

    @Test fun acceptsExplicitTwoOrMoreOriginalContentUris() {
        val streams = listOf("content://gallery/1", "content://gallery/2")
        assertEquals(streams, ShareEntryPolicy.chooseMultipleOriginals(
            multi, streams, listOf("content://gallery/thumbnail")))
        assertNull(ShareEntryPolicy.chooseMultipleOriginals(one, streams, emptyList()))
    }

    @Test fun clipdataIsOnlyFallbackAndNeverCombinedWithExtras() {
        val streams = listOf("content://gallery/1", "content://gallery/2")
        assertEquals(streams, ShareEntryPolicy.chooseMultipleOriginals(
            multi, null, streams))
        assertNull(ShareEntryPolicy.chooseMultipleOriginals(
            multi, listOf("content://gallery/1"), streams))
    }

    @Test fun rejectsInvalidDuplicatesAndExcessiveSelections() {
        val valid = (1..32).map { "content://gallery/$it" }
        assertEquals(32, ShareEntryPolicy.chooseMultipleOriginals(multi, valid, emptyList())?.size)
        assertNull(ShareEntryPolicy.chooseMultipleOriginals(multi,
            listOf("content://gallery/1"), emptyList()))
        assertNull(ShareEntryPolicy.chooseMultipleOriginals(multi, valid +
            "content://gallery/33", emptyList()))
        assertNull(ShareEntryPolicy.chooseMultipleOriginals(multi,
            listOf("content://gallery/1", "content://gallery/1"), emptyList()))
        assertNull(ShareEntryPolicy.chooseMultipleOriginals(multi,
            listOf("content://gallery/1", "file:///sdcard/a.png"), emptyList()))
        assertNull(ShareEntryPolicy.chooseMultipleOriginals(multi,
            listOf("content://gallery/1", null), emptyList()))
    }
}
