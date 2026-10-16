package tw.airgesture.transferbeta.core

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A pinned peer identity, NOT a Nearby endpoint name or authenticationDigits.
 * Each session authenticates fresh 32-byte nonces with ECDSA P-256.
 */
object PeerIdentityProtocol {
    const val PROTOCOL_VERSION = 1
    // Existing v0.2.0 installs use UUID.take(12), including the hyphen.
    private val idPattern = Regex("(?:[a-f0-9]{12}|[a-f0-9]{8}-[a-f0-9]{3})")
    fun validPeerId(value: String): Boolean = idPattern.matches(value)

    fun validNonce(encoded: String): Boolean = try {
        decode(encoded).size == 32
    } catch (_: Exception) { false }

    fun decode(encoded: String): ByteArray = Base64.getDecoder().decode(encoded)
    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun parseKey(encoded: String): PublicKey {
        val bytes = decode(encoded)
        require(bytes.size in 70..200) { "Invalid P-256 public key size" }
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        require(pub.algorithm == "EC") { "Expected EC key" }
        val ec = pub as java.security.interfaces.ECPublicKey
        require(ec.params.curve.field.fieldSize == 256) { "Expected P-256 EC key" }
        return pub
    }
    fun encodedKey(key: PublicKey): String = encode(key.encoded)

    /** Canonical ordering is intentionally directional; sign and verify mirror the sender. */
    fun proofMessage(
        signerId: String, otherId: String,
        signerPublic: String, otherPublic: String,
        signerNonce: String, otherNonce: String
    ): ByteArray {
        require(validPeerId(signerId) && validPeerId(otherId) && signerId != otherId)
        require(validNonce(signerNonce) && validNonce(otherNonce))
        require(signerPublic != otherPublic)
        val parts = listOf("AirGrab-peer-auth-v1", signerId, otherId,
            signerPublic, otherPublic, signerNonce, otherNonce)
        return parts.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
    }

    fun sign(keyPair: KeyPair, message: ByteArray): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(message)
        return encode(signer.sign())
    }

    fun verify(encodedPublic: String, message: ByteArray, signature: String): Boolean =
        try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(parseKey(encodedPublic))
            verifier.update(message)
            verifier.verify(decode(signature))
        } catch (_: Exception) { false }

    fun matchesPinned(
        pinnedId: String?, pinnedKey: String?, claimedId: String, claimedKey: String
    ): Boolean = validPeerId(claimedId) &&
        pinnedId == claimedId && pinnedKey == claimedKey &&
        try { parseKey(claimedKey); true } catch (_: Exception) { false }
}
