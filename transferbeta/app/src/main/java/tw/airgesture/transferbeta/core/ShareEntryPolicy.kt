package tw.airgesture.transferbeta.core

/** Strict share intake: only one explicitly granted content URI or validated URL. */
object ShareEntryPolicy {
    fun allowedFileScheme(scheme: String?): Boolean = scheme == "content"
    fun validItemCount(count: Int): Boolean = count in 0..1

    /**
     * ACTION_SEND with EXTRA_STREAM explicitly names ONE selected file.
     * Samsung Gallery may also include multiple auxiliary ClipData grants; its
     * item count cannot be used to reject the single EXTRA_STREAM.
     * If there is no explicit stream, require exactly one distinct clip URI.
     */
    fun chooseSingleOriginal(
        action: String?, explicitStream: String?, clipUris: List<String?>
    ): String? {
        if (!shouldHandleSilently(action)) return null
        if (explicitStream != null) {
            return explicitStream.takeIf { it.startsWith("content://", ignoreCase = true) }
        }
        return clipUris.filterNotNull()
            .filter { it.startsWith("content://", ignoreCase = true) }
            .distinct().singleOrNull()
    }
    fun shouldHandleSilently(action: String?): Boolean =
        action == "android.intent.action.SEND"
}