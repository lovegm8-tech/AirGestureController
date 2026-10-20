package tw.airgesture.transferbeta.crossplatform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Dedicated AndroidKeyStore P-256 identity. iOS pins SHA-256 of X9.63
 * uncompressed public-key bytes on first user-approved tap (TOFU).
 * Never reuse Android↔Android ECDSA pairing material.
 */
object IosBridgeIdentity {
    private const val ALIAS = "airgrab.iosbridge.identity.p256.v1"
    @Synchronized private fun store(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    @Synchronized private fun ensure(): KeyStore {
        val ks = store()
        if (!ks.containsAlias(ALIAS)) {
            val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generator.generateKeyPair()
        }
        return store()
    }
    private fun padded(value: BigInteger): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(raw.size <= 32)
        return ByteArray(32 - raw.size) + raw
    }
    fun publicX963(): ByteArray {
        val pub = ensure().getCertificate(ALIAS).publicKey as ECPublicKey
        return byteArrayOf(0x04) + padded(pub.w.affineX) + padded(pub.w.affineY)
    }
    fun sign(message: ByteArray): ByteArray {
        val key = ensure().getKey(ALIAS, null) as java.security.PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key); update(message); sign()
        }
    }
}
