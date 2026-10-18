package tw.airgesture.controller.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.CompletableFuture

/**
 * Adapter for the separate pre-existing Android "隔空抓取" flow.
 *
 * After the old grab handler resolves a user-authorized content URI or webpage URL,
 * call one of these methods. The old gesture engine must remain the owner of deciding
 * WHEN to capture and WHAT content the user selected. We never screen-scrape or infer it.
 *
 * The opt-in TransferService must already be started from TransferActivity when the
 * foreground-service-start restriction applies.
 */
object ExistingGrabBridge {
    fun offerFile(context: Context, uri: Uri, mime: String): CompletableFuture<Int> {
        val app = context.applicationContext
        return CompletableFuture.supplyAsync {
            TransferStore.enqueueShare(app, Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    fun offerWebPage(context: Context, url: String): CompletableFuture<Int> {
        require(url.startsWith("https://") || url.startsWith("http://"))
        val app = context.applicationContext
        return CompletableFuture.supplyAsync {
            TransferStore.enqueueShare(app, Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            })
        }
    }
}
