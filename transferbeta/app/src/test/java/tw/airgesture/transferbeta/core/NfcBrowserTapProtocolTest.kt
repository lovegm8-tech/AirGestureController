package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class NfcBrowserTapProtocolTest {
    @Test fun separateWebTapCommandFromExistingNfcShare() {
        val browser = NfcTapProtocol.getBrowserSessionCommand()
        assertTrue(NfcTapProtocol.isBrowserSessionCommand(browser))
        assertFalse(NfcTapProtocol.isSessionCommand(browser))
        assertFalse(NfcTapProtocol.isBrowserSessionCommand(NfcTapProtocol.getSessionCommand()))
    }

    @Test fun sessionRoundTripAndMalformedReplyRejected() {
        val id = "abcdef123456"
        val nonce = "0123456789abcdef0123456789abcdef"
        val decoded = NfcTapProtocol.decodeSessionResponse(
            NfcTapProtocol.sessionResponse(id, nonce))
        assertEquals(id, decoded?.peerId)
        assertEquals(nonce, decoded?.nonce)
        assertNull(NfcTapProtocol.decodeSessionResponse(
            NfcTapProtocol.notReadyResponse()))
    }
}
