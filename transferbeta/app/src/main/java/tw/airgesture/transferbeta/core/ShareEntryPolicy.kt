package tw.airgesture.transferbeta.core

/** Strict share intake: only one explicitly granted content URI or validated URL. */
object ShareEntryPolicy {
    fun allowedFileScheme(scheme: String?): Boolean = scheme == "content"
    fun validItemCount(count: Int): Boolean = count in 0..1
    fun shouldHandleSilently(action: String?): Boolean =
        action == "android.intent.action.SEND"
}