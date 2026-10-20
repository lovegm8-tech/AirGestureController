package tw.airgesture.transferbeta.crossplatform

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirGrab iOS Bridge v1, intentionally independent of Android↔Android NFC AID.
 * Every number here is network byte order. See docs/IOS_NFC_WIFI_PROTOCOL_V1.md.
 */
object IosBridgeProtocol {
    const val AID = "F041495247524149" // distinct from Android AID ...4142
    const val MAJOR = 1
    const val MAX_FILES = 32
    const val CHUNK_BYTES = 48 * 1024
    const val MAX_FRAME_BYTES = 64 * 1024
    const val MAX_CONTROL_BYTES = 16 * 1024
    const val ARM_LIFETIME_MS = 120_000L
    const val TCP_TIMEOUT_MS = 30_000
    val OK = byteArrayOf(0x90.toByte(), 0x00)
    val NOT_READY = byteArrayOf(0x69, 0x85.toByte())
    val BAD_INS = byteArrayOf(0x6D, 0x00)
    val BAD_DATA = byteArrayOf(0x6A, 0x80.toByte())
    val READ_HELLO = byteArrayOf(0x80.toByte(), 0x20, 0, 0, 0)
    private const val INS_AUTH = 0x21
    private val random = SecureRandom()

    fun fresh(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)
    fun selectAid(): ByteArray {
        val aid = hex(AID)
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) +
            aid + byteArrayOf(0)
    }
    fun isSelect(apdu: ByteArray?): Boolean {
        val select = selectAid()
        // Core NFC/ISO7816 stacks may omit or supply the trailing Le byte.
        return apdu != null && (apdu.contentEquals(select) ||
            apdu.contentEquals(select.copyOf(select.size - 1)))
    }
    fun isReadHello(apdu: ByteArray?): Boolean = apdu?.contentEquals(READ_HELLO) == true
    fun authBody(apdu: ByteArray?): ByteArray? {
        if (apdu == null || apdu.size !in 53..54 ||
            (apdu[0].toInt() and 0xff) != 0x80 ||
            (apdu[1].toInt() and 0xff) != INS_AUTH ||
            apdu[2].toInt() != 0 || apdu[3].toInt() != 0 ||
            (apdu[4].toInt() and 0xff) != 48) return null
        return apdu.copyOfRange(5, 53)
    }
    fun hex(text: String): ByteArray = ByteArray(text.length / 2) { i ->
        text.substring(2*i, 2*i+2).toInt(16).toByte()
    }
    fun hello(sid: ByteArray, ipv4: ByteArray, port: Int,
              androidNonce: ByteArray, secret: ByteArray, identityX963: ByteArray): ByteArray {
        require(sid.size == 16 && ipv4.size == 4 && port in 1..65535)
        require(androidNonce.size == 16 && secret.size == 32 && identityX963.size == 65)
        return "AGI1".toByteArray(Charsets.US_ASCII) + byteArrayOf(MAJOR.toByte()) +
            sid + ipv4 + ByteBuffer.allocate(2).putShort(port.toShort()).array() +
            androidNonce + secret + identityX963
    }
    fun hmac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
        }
    fun authenticate(session: Session, iosNonce: ByteArray, proof: ByteArray): Boolean {
        if (iosNonce.size != 16 || proof.size != 32) return false
        val expected = hmac(session.secret,
            "NFC-AUTH-V1".toByteArray(Charsets.US_ASCII) + session.hello + iosNonce)
        return MessageDigest.isEqual(proof, expected)
    }
    fun authenticateTcp(session: Session, iosNonce: ByteArray, proof: ByteArray): Boolean {
        if (!session.authorized || !MessageDigest.isEqual(iosNonce, session.iosNonce ?: return false))
            return false
        val expected = hmac(session.secret,
            "TCP-AUTH-V1".toByteArray(Charsets.US_ASCII) + session.sid + iosNonce)
        return MessageDigest.isEqual(proof, expected)
    }
    /** RFC 5869 HKDF with salt = SID || both 16-byte NFC nonces. */
    fun sessionKey(s: Session, purpose: String): ByteArray {
        val salt = s.sid + s.androidNonce + requireNotNull(s.iosNonce)
        val prk = hmac(salt, s.secret)
        return hmac(prk, ("AIRGRAB-IOS-WIFI-V1/" + purpose).toByteArray(Charsets.US_ASCII) +
            byteArrayOf(1))
    }
    class Session(
        val sid: ByteArray, val androidNonce: ByteArray, val secret: ByteArray,
        val hello: ByteArray, val createdAt: Long
    ) {
        @Volatile var authorized: Boolean = false
        @Volatile var iosNonce: ByteArray? = null
        fun isExpired(now: Long): Boolean = now - createdAt !in 0..ARM_LIFETIME_MS
    }

    /**
     * Chunked AES-256-GCM transport. Counter/nonce never repeats within a key;
     * each direction has its OWN HKDF key and nonce domain. Frame boundaries
     * are 4-byte big-endian ciphertext lengths; authenticated plaintext begins
     * with 0x01 (JSON) or 0x02 (binary).
     */
    class FrameCipher(private val session: Session, private val androidSends: Boolean) {
        private val outgoing = sessionKey(session, if (androidSends) "a2i" else "i2a")
        private val incoming = sessionKey(session, if (androidSends) "i2a" else "a2i")
        private var sendSeq = 0L
        private var receiveSeq = 0L
        private fun nonce(domain: String, counter: Long): ByteArray =
            domain.toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(8).putLong(counter).array()
        private fun crypt(encrypt: Boolean, key: ByteArray, domain: String,
                          counter: Long, payload: ByteArray): ByteArray {
            require(counter >= 0 && payload.size <= MAX_FRAME_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(domain, counter)))
            cipher.updateAAD(session.sid + ByteBuffer.allocate(8).putLong(counter).array())
            return cipher.doFinal(payload)
        }
        fun seal(plain: ByteArray): ByteArray {
            require(plain.size in 1..MAX_FRAME_BYTES - 16)
            return crypt(true, outgoing, if (androidSends) "A2I1" else "I2A1",
                sendSeq++, plain)
        }
        fun open(data: ByteArray): ByteArray {
            require(data.size in 17..MAX_FRAME_BYTES)
            return crypt(false, incoming, if (androidSends) "I2A1" else "A2I1",
                receiveSeq++, data)
        }
    }
}
