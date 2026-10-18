package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class TransferCapacityTest {
    @Test fun largeOriginalVideoUpTo2GiBAccepted() {
        val valid = "a".repeat(64)
        val almost2gb = TransferPolicy.MAX_BYTES - 1
        assertTrue(TransferPolicy.validFileOffer(
            "123456789012", "original-video.mov", almost2gb, valid))
        assertFalse(TransferPolicy.validFileOffer(
            "123456789012", "too-large.mov", TransferPolicy.MAX_BYTES + 1, valid))
        assertEquals("2 GB", TransferPolicy.capacityLabel())
    }

    @Test fun receiverMustHaveCompleteFileSizePlusReserve() {
        assertTrue(TransferPolicy.hasRoom(
            TransferPolicy.MAX_BYTES + TransferPolicy.MIN_FREE_HEADROOM,
            TransferPolicy.MAX_BYTES))
        assertFalse(TransferPolicy.hasRoom(
            TransferPolicy.MAX_BYTES, TransferPolicy.MAX_BYTES))
        assertFalse(TransferPolicy.hasRoom(-1, 100))
        assertFalse(TransferPolicy.hasRoom(100, -1))
        assertFalse(TransferPolicy.hasRoom(Long.MAX_VALUE,
            TransferPolicy.MAX_BYTES + 1))
    }
}
