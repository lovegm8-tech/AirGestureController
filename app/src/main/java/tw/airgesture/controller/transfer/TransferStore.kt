package tw.airgesture.controller.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** All offers must originate from an explicit Android share action. */
object TransferStore {
    private const val OFFER_LIFETIME_MS = 10 * 60 * 1000L
    private val offers = ConcurrentHashMap<String, Offer>()

    data class Offer(val id: String, val file: File, val name: String,
                     val mime: String, val created: Long = System.currentTimeMillis())

    fun key(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("airgrab_pairing_v1", Context.MODE_PRIVATE)
        synchronized(this) {
            val existing = prefs.getString("secret", null)
            if (existing != null) return android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            check(prefs.edit().putString("secret",
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit())
            return bytes
        }
    }

    fun enqueueShare(context: Context, intent: Intent): Int {
        var count = 0
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        }
        for (uri in uris) {
            val providedName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: uri.lastPathSegment ?: "shared-file"
            val dest = newDestination(context)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Cannot open shared URI")
                offers[dest.name] = Offer(dest.name, dest, safeName(providedName),
                    intent.type ?: "application/octet-stream")
                count++
            } catch (_: Exception) { dest.delete() }
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank() && uris.isEmpty()) {
            val file = newDestination(context)
            file.writeText(text, Charsets.UTF_8)
            val isUrl = text.trim().startsWith("https://") || text.trim().startsWith("http://")
            offers[file.name] = Offer(file.name, file, if (isUrl) "shared-link.txt" else "shared-text.txt",
                "text/plain")
            count++
        }
        prune()
        return count
    }

    private fun newDestination(context: Context): File {
        val dir = File(context.filesDir, "airgrab/offers").apply { mkdirs() }
        return File(dir, UUID.randomUUID().toString())
    }

    fun list(): JSONArray {
        prune()
        return JSONArray().apply {
            offers.values.sortedByDescending { it.created }.forEach { offer ->
                put(JSONObject().put("id", offer.id).put("name", offer.name)
                    .put("mime", offer.mime).put("size", offer.file.length()))
            }
        }
    }

    fun get(id: String): Offer? { prune(); return offers[id] }

    private fun prune() {
        val now = System.currentTimeMillis()
        for ((id, offer) in offers) {
            if (now - offer.created > OFFER_LIFETIME_MS && offers.remove(id, offer))
                offer.file.delete()
        }
    }

    fun safeName(name: String): String = name.replace(Regex("[/\\\\\\x00-\\x1f]"), "_")
        .trim().take(120).ifEmpty { "file" }

    fun digest(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
