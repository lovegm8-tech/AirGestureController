package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class NfcTapSessionCodecTest {
    private val id = "abc123def456"
    private val nonce = "0123456789abcdef0123456789abcdef"

    @Test fun roundTrip() {
        val message = NfcTapSessionCodec.encode(id, nonce)
        assertEquals(id, NfcTapSessionCodec.decode(message)?.peerId)
        assertEquals(nonce, NfcTapSessionCodec.decode(message)?.nonce)
    }

    @Test fun rejectMalformedOrInjectedTokens() {
        assertNull(NfcTapSessionCodec.decode(null))
        assertNull(NfcTapSessionCodec.decode("AIRGRAB2|abc123def456|bad".toByteArray()))
        assertNull(NfcTapSessionCodec.decode("AIRGRAB3|$id|$nonce".toByteArray()))
        assertNull(NfcTapSessionCodec.decode("AIRGRAB2|foo\\nbarxxxxx|$nonce".toByteArray()))
    }

    @Test fun rejectMalformedIdentityAtEncoding() {
        try {
            NfcTapSessionCodec.encode("another-length", nonce)
            fail("Invalid ID must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
