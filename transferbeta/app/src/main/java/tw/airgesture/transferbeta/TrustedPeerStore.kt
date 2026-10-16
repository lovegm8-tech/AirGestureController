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

    /** Multiple pinned peers. Migrates the original one-peer prefs without replacing keys. */
    fun trustedPeers(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        // Legacy v0.2.5 pairing must survive upgrading the app.
        val originalId = prefs.getString("peer_id", null)
        val originalKey = prefs.getString("peer_pub", null)
        if (originalId != null && originalKey != null &&
            PeerIdentityProtocol.matchesPinned(originalId, originalKey, originalId, originalKey))
            result[originalId] = originalKey
        val ids = prefs.getStringSet("trusted_ids_v2", emptySet()) ?: emptySet()
        for (id in ids.sorted()) {
            val pub = prefs.getString("trusted_pub_$id", null) ?: continue
            if (PeerIdentityProtocol.matchesPinned(id, pub, id, pub)) result[id] = pub
        }
        return result
    }

    fun trustedId(): String? = trustedPeers().keys.firstOrNull()
    fun trustedKey(): String? = trustedId()?.let { trustedPeers()[it] }
    fun isTrusted(id: String, public: String): Boolean =
        PeerIdentityProtocol.matchesPinned(id, trustedPeers()[id], id, public)
    fun isRecognizedHint(id: String): Boolean =
        PeerIdentityProtocol.validPeerId(id) && trustedPeers().containsKey(id)
    fun rememberedIds(): List<String> = trustedPeers().keys.toList()

    fun pinAfterVerifiedFirstPairing(id: String, public: String) {
        require(PeerIdentityProtocol.validPeerId(id))
        PeerIdentityProtocol.parseKey(public)
        val existing = trustedPeers()[id]
        check(existing == null || existing == public) {
            "Pinned device identity cannot be silently replaced"
        }
        check(existing != null || trustedPeers().size < 8) {
            "AirGrab supports up to eight trusted devices"
        }
        val updatedIds = (prefs.getStringSet("trusted_ids_v2", emptySet())
            ?: emptySet()).toMutableSet().apply { add(id) }
        check(prefs.edit().putString("trusted_pub_$id", public)
            .putStringSet("trusted_ids_v2", updatedIds).commit()) {
            "Cannot persist trusted peer"
        }
    }

    fun forget(id: String) {
        val updated = (prefs.getStringSet("trusted_ids_v2", emptySet())
            ?: emptySet()).toMutableSet().apply { remove(id) }
        val editor = prefs.edit().remove("trusted_pub_$id")
            .putStringSet("trusted_ids_v2", updated)
        if (prefs.getString("peer_id", null) == id) {
            editor.remove("peer_id").remove("peer_pub")
        }
        check(editor.commit()) { "Cannot forget device" }
    }

    fun forget() {
        for (id in rememberedIds()) forget(id)
    }
}
