package tw.airgesture.transferbeta

import android.content.Context
import java.security.SecureRandom

/**
 * One single-use NFC nonce, issued only when an existing verified Nearby
 * receiver is active. The nonce is NOT a device identity.
 */
object NfcTapSessionStore {
    private const val PREF = "nfc_tap_session"
    private const val TTL_MS = 45_000L
    private val random = SecureRandom()

    @Synchronized fun issue(context: Context): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("nonce", nonce)
            .putLong("at", System.currentTimeMillis()).commit()
        return nonce
    }

    @Synchronized fun consume(context: Context, nonce: String): Boolean {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val saved = prefs.getString("nonce", null)
        val issuedAt = prefs.getLong("at", 0L)
        val current = System.currentTimeMillis()
        val matches = saved != null && issuedAt > 0 &&
            current >= issuedAt && current - issuedAt <= TTL_MS &&
            java.security.MessageDigest.isEqual(
                saved.toByteArray(Charsets.US_ASCII), nonce.toByteArray(Charsets.US_ASCII))
        if (matches) prefs.edit().remove("nonce").remove("at").commit()
        return matches
    }
}
