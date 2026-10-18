package tw.airgesture.transferbeta.ioslink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException

class SecureChannelTest {
    @Test fun encryptedChunkRoundTripDoesNotLeakPlaintext() {
        val key = ByteArray(32) { (it + 11).toByte() }
        val clear = ByteArray(256 * 1024) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val sender = SecureChannel(ByteArrayInputStream(byteArrayOf()), out, key)
        sender.sendBytes(clear, clear.size)
        val packet = out.toByteArray()
        assertEquals(packet.size - 4, ((packet[0].toInt() and 255) shl 24) or
            ((packet[1].toInt() and 255) shl 16) or
            ((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255))
        val receiver = SecureChannel(ByteArrayInputStream(packet), ByteArrayOutputStream(), key)
        val frame = receiver.receive()
        assertEquals(2, frame.type)
        assertArrayEquals(clear, frame.payload)
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongPairingKeyCannotDecrypt() {
        val payload = ByteArrayOutputStream()
        SecureChannel(ByteArrayInputStream(byteArrayOf()), payload, ByteArray(32) { 7 })
            .sendBytes(byteArrayOf(1, 2, 3), 3)
        SecureChannel(ByteArrayInputStream(payload.toByteArray()),
            ByteArrayOutputStream(), ByteArray(32) { 9 }).receive()
    }
}
