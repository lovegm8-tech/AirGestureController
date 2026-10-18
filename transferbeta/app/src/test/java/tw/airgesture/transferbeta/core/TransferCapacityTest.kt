package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class TransferCapacityTest {
    @Test fun largeOriginalVideosNotCappedAt2GiB() {
        val valid = "a".repeat(64)
        val over2GiB = 7L * 1024L * 1024L * 1024L
        assertTrue(TransferPolicy.validFileOffer(
            "123456789012", "original-video.mov", over2GiB, valid))
        assertFalse(TransferPolicy.validFileOffer(
            "123456789012", "too-large.mov", TransferPolicy.MAX_BYTES + 1, valid))
        assertNotEquals("2 GB", TransferPolicy.capacityLabel())
    }

    @Test fun receiverMustHaveCompleteFileSizePlusReserve() {
        assertTrue(TransferPolicy.hasRoom(
            TransferPolicy.MAX_BYTES * 2 + TransferPolicy.MIN_FREE_HEADROOM,
            TransferPolicy.MAX_BYTES))
        assertFalse(TransferPolicy.hasRoom(
            TransferPolicy.MAX_BYTES + TransferPolicy.MIN_FREE_HEADROOM,
            TransferPolicy.MAX_BYTES))
        assertFalse(TransferPolicy.hasRoom(
            TransferPolicy.MAX_BYTES, TransferPolicy.MAX_BYTES))
        assertFalse(TransferPolicy.hasRoom(-1, 100))
        assertFalse(TransferPolicy.hasRoom(100, -1))
        assertFalse(TransferPolicy.hasRoom(Long.MAX_VALUE,
            TransferPolicy.MAX_BYTES + 1))
        // Capacity checks must not overflow on very large adversarial values.
        assertFalse(TransferPolicy.hasRoom(Long.MAX_VALUE, Long.MAX_VALUE))
        assertTrue(TransferPolicy.hasRoom(
            16L * 1024L * 1024L * 1024L,
            7L * 1024L * 1024L * 1024L))
    }
}
