package tw.airgesture.controller.transfer

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirGrab v1: uint32-be length, then 12-byte random nonce + AES-256-GCM ciphertext/tag.
 * Decrypted bytes: 1-byte type (1 JSON, 2 binary), followed by payload.
 * The key is random per installation and shared only through an explicit pairing QR code.
 */
class SecureChannel(input: InputStream, output: OutputStream, private val key: ByteArray) {
    private val reader = DataInputStream(BufferedInputStream(input, 512 * 1024))
    private val writer = DataOutputStream(BufferedOutputStream(output, 512 * 1024))
    private val random = SecureRandom()

    init { require(key.size == 32) { "Expected 256-bit pairing key" } }

    fun sendJson(value: JSONObject) = send(1, value.toString().toByteArray(Charsets.UTF_8))
    fun sendBytes(value: ByteArray, size: Int) = send(2, value.copyOf(size))

    @Synchronized private fun send(type: Int, bytes: ByteArray) {
        require(bytes.size <= MAX_PAYLOAD)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(byteArrayOf(type.toByte()) + bytes)
        writer.writeInt(nonce.size + encrypted.size)
        writer.write(nonce)
        writer.write(encrypted)
        writer.flush()
    }

    fun receive(): Frame {
        val length = reader.readInt()
        require(length in 29..(MAX_PAYLOAD + 29)) { "Invalid encrypted frame length" }
        val encrypted = ByteArray(length)
        reader.readFully(encrypted)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        val plain = cipher.doFinal(encrypted, 12, length - 12)
        require(plain.isNotEmpty() && (plain[0] == 1.toByte() || plain[0] == 2.toByte()))
        return Frame(plain[0].toInt(), plain.copyOfRange(1, plain.size))
    }

    fun receiveJson(): JSONObject {
        val frame = receive()
        require(frame.type == 1) { "Expected JSON control frame" }
        return JSONObject(frame.payload.toString(Charsets.UTF_8))
    }

    data class Frame(val type: Int, val payload: ByteArray)
    companion object {
        const val MAX_PAYLOAD = 512 * 1024
        const val CHUNK_SIZE = 256 * 1024
    }
}
