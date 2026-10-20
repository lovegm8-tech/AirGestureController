package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test
import tw.airgesture.transferbeta.crossplatform.IosBridgeProtocol

/** Pure-JVM transport tests; no Samsung NFC, Wi-Fi, or Android Nearby needed. */
class IosBridgeProtocolTest {
    private fun session(): IosBridgeProtocol.Session {
        val sid = ByteArray(16) { it.toByte() }
        val androidNonce = ByteArray(16) { (it + 1).toByte() }
        val secret = ByteArray(32) { (it + 4).toByte() }
        val hello = IosBridgeProtocol.hello(sid, byteArrayOf(192.toByte(), 168.toByte(), 1, 22),
            43210, androidNonce, secret, byteArrayOf(4) + ByteArray(64) { it.toByte() })
        return IosBridgeProtocol.Session(sid, androidNonce, secret, hello, 0L)
    }

    @Test fun distinctAidsAndSelectFormats() {
        assertNotEquals("F041495247524142", IosBridgeProtocol.AID)
        val select = IosBridgeProtocol.selectAid()
        assertTrue(IosBridgeProtocol.isSelect(select))
        assertTrue(IosBridgeProtocol.isSelect(select.copyOf(select.size - 1)))
        assertFalse(IosBridgeProtocol.isSelect(byteArrayOf(0, 0)))
    }

    @Test fun helloFieldsHaveStableWireOffsets() {
        val h = session().hello
        assertEquals(140, h.size)
        assertEquals("AGI1", String(h.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(1, h[4].toInt())
        assertEquals(43210, (h[25].toInt() and 255) * 256 + (h[26].toInt() and 255))
        assertEquals(4, h[75].toInt())
    }

    @Test fun rejectsWrongNfcProof() {
        val s = session()
        val iosNonce = ByteArray(16) { 7 }
        val proof = IosBridgeProtocol.hmac(s.secret,
            "NFC-AUTH-V1".toByteArray() + s.hello + iosNonce)
        assertTrue(IosBridgeProtocol.authenticate(s, iosNonce, proof))
        assertFalse(IosBridgeProtocol.authenticate(s, iosNonce, ByteArray(32)))
        assertFalse(IosBridgeProtocol.authenticate(s, ByteArray(15), proof))
        val apdu = byteArrayOf(0x80.toByte(), 0x21, 0, 0, 48) + iosNonce + proof
        assertArrayEquals(iosNonce + proof, IosBridgeProtocol.authBody(apdu))
        assertNull(IosBridgeProtocol.authBody(byteArrayOf(0, 0)))
    }

    @Test fun aesGcmWorksInBothDirectionsAndRejectsReplay() {
        val s = session()
        s.iosNonce = ByteArray(16) { 8 }
        s.authorized = true
        val android = IosBridgeProtocol.FrameCipher(s, androidSends = true)
        val ios = IosBridgeProtocol.FrameCipher(s, androidSends = false)
        val msg = "first Android message".toByteArray()
        val encrypted = android.seal(msg)
        assertArrayEquals(msg, ios.open(encrypted))
        try {
            ios.open(encrypted)
            fail("replay must fail authenticated counter")
        } catch (_: Exception) { }
        val back = "hello Android".toByteArray()
        assertArrayEquals(back, android.open(ios.seal(back)))
    }

    @Test fun protocolRejectsOversizedFrames() {
        val s = session()
        s.iosNonce = ByteArray(16) { 8 }
        val sender = IosBridgeProtocol.FrameCipher(s, true)
        try {
            sender.seal(ByteArray(IosBridgeProtocol.MAX_FRAME_BYTES))
            fail("oversized plaintext must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
