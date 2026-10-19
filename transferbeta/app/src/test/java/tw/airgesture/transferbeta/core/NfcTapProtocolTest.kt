package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class NfcTapProtocolTest {
    @Test fun readerSelectHandshakesWithOurCard() {
        val select = NfcTapProtocol.selectApdu()
        assertEquals(14, select.size)
        assertTrue(NfcTapProtocol.isSuccessfulProbe(NfcTapProtocol.respond(select)))
    }

    @Test fun nfcSessionApduStaysDistinctFromReadOnlyProbe() {
        val request = NfcTapProtocol.getSessionCommand()
        assertTrue(NfcTapProtocol.isSessionCommand(request))
        assertFalse(NfcTapProtocol.isSessionCommand(NfcTapProtocol.selectApdu()))
        val reply = NfcTapProtocol.sessionResponse("abc123def456",
            "0123456789abcdef0123456789abcdef")
        assertEquals("abc123def456",
            NfcTapProtocol.decodeSessionResponse(reply)?.peerId)
        assertNull(NfcTapProtocol.decodeSessionResponse(
            NfcTapProtocol.notReadyResponse()))
    }

    @Test fun unrelatedAidIsRejected() {
        val other = NfcTapProtocol.selectApdu()
        other[8] = (other[8].toInt() xor 0x20).toByte()
        assertFalse(NfcTapProtocol.isSuccessfulProbe(NfcTapProtocol.respond(other)))
        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), NfcTapProtocol.respond(other))
    }

    @Test fun nonSelectCannotTriggerProbe() {
        assertArrayEquals(byteArrayOf(0x6D, 0),
            NfcTapProtocol.respond(byteArrayOf(0x00, 0x00, 0x00, 0x00)))
        assertFalse(NfcTapProtocol.isSuccessfulProbe(null))
        assertFalse(NfcTapProtocol.isSuccessfulProbe(byteArrayOf(0x90.toByte(), 0)))
    }

    @Test fun truncatedApduCannotPassValidation() {
        val select = NfcTapProtocol.selectApdu()
        for (length in 0 until select.size - 1) {
            assertFalse(NfcTapProtocol.isSuccessfulProbe(NfcTapProtocol.respond(
                select.copyOf(length))))
        }
    }

    @Test fun nfcProbeContainsNoPhotoOrIdentityAndIsNotTransferAuthorization() {
        val response = NfcTapProtocol.respond(NfcTapProtocol.selectApdu())
        assertEquals("AIRGRAB:NFC-PROBE:1",
            response.copyOf(response.size - 2).toString(Charsets.US_ASCII))
        // The response is a public protocol marker, not an authenticated ID.
        assertEquals(2 + "AIRGRAB:NFC-PROBE:1".length, response.size)
    }
}
