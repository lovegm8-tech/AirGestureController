package tw.airgesture.transferbeta.ioslink

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/** Adapter between current Floating Control grab and iPhone LAN transport. */
object TransferRelay {
    private val queue = Executors.newSingleThreadExecutor()

    fun isEnabled(context: Context): Boolean = TransferService.isServing

    fun offerLink(context: Context, url: String): Boolean {
        if (!isEnabled(context)) return false
        return try { TransferStore.stageLink(context.applicationContext, url); true }
        catch (_: Exception) { false }
    }

    fun offerFile(context: Context, source: File, name: String): Boolean {
        if (!isEnabled(context) || !source.isFile || source.length() <= 0) return false
        val app = context.applicationContext
        queue.execute {
            // Staged copy remains intact even if the original Android Nearby
            // transfer deletes its own temporary source after completion.
            try { TransferStore.stageFile(app, source, name) }
            catch (_: Exception) { /* an incomplete offer is never advertised */ }
        }
        return true
    }
}
