package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class TransferPolicyTest {
    @Test fun cannotInjectDirectoryTraversal() {
        assertEquals("notes.docx", TransferPolicy.safeName("../../notes.docx"))
        assertNull(TransferPolicy.safeName(".."))
    }
    @Test fun rejectsMissingOrInvalidHash() {
        assertFalse(TransferPolicy.validFileOffer("session-123456",
            "report.xlsx",100,"abc"))
        assertFalse(TransferPolicy.validFileOffer("session-123456",
            "report.xlsx",-1,"0".repeat(64)))
    }
    @Test fun correctDigestAndLengthBothRequired() {
        val sha="a".repeat(64)
        assertTrue(TransferPolicy.matchingHash(sha,sha,200,200))
        assertFalse(TransferPolicy.matchingHash(sha,"b".repeat(64),200,200))
        assertFalse(TransferPolicy.matchingHash(sha,sha,200,199))
    }
    @Test fun rejectsNonHttpLink() {
        assertFalse(TransferPolicy.validLinkOffer("session-123456","file:///tmp/private"))
        assertTrue(TransferPolicy.validLinkOffer("session-123456","https://example.com/watch"))
    }
}
