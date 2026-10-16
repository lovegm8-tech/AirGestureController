package tw.airgesture.transferbeta

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import tw.airgesture.transferbeta.core.PeerIdentityProtocol
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

/**
 * Local private key stays non-exportable in Android Keystore.
 * Only the verified other device's public key is saved to app-private prefs.
 * Avoids Android auto-backup to prevent restores with a lost Keystore key.
 */
class TrustedPeerStore(private val app: Context) {
    private val prefs = app.getSharedPreferences("airgrab_trusted_device_v1", Context.MODE_PRIVATE)
    private val alias = "airgrab_device_identity_p256_v1"

    fun identity(): KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val certificate = store.getCertificate(alias)
        val existing = store.getKey(alias, null) as? java.security.PrivateKey
        if (existing != null && certificate != null) {
            return KeyPair(certificate.publicKey, existing)
        }
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build())
        return generator.generateKeyPair()
    }

    fun randomNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return PeerIdentityProtocol.encode(bytes)
    }

    fun trustedId(): String? = prefs.getString("peer_id", null)
    fun trustedKey(): String? = prefs.getString("peer_pub", null)

    fun isTrusted(id: String, public: String): Boolean =
        PeerIdentityProtocol.matchesPinned(trustedId(), trustedKey(), id, public)

    fun isRecognizedHint(id: String): Boolean =
        PeerIdentityProtocol.validPeerId(id) && trustedId() == id && trustedKey() != null

    fun pinAfterVerifiedFirstPairing(id: String, public: String) {
        require(PeerIdentityProtocol.validPeerId(id))
        PeerIdentityProtocol.parseKey(public)
        // A DIFFERENT valid public key must never silently replace a pinned one.
        check(trustedId() == null && trustedKey() == null ||
            isTrusted(id, public)) { "Unexpected trusted device change" }
        check(prefs.edit().putString("peer_id", id)
            .putString("peer_pub", public).commit()) { "Cannot persist trusted device" }
    }

    fun forget() {
        check(prefs.edit().remove("peer_id").remove("peer_pub").commit())
    }
}
