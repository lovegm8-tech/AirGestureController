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
    /**
     * Multi-share must come from the explicit Android EXTRA_STREAM list.
     * ClipData may contain thumbnails and other supplementary grants.
     * Only when EXTRA_STREAM is absent do we accept distinct ClipData URIs.
     */
    fun chooseMultipleOriginals(
        action: String?,
        explicitStreams: List<String?>?,
        clipUris: List<String?>
    ): List<String>? {
        if (action != "android.intent.action.SEND_MULTIPLE") return null
        val picked = explicitStreams ?: clipUris
        if (picked.size !in 2..AirGrabBundle.MAX_FILES) return null
        if (picked.any { it == null || !it.startsWith("content://", ignoreCase = true) })
            return null
        val values = picked.filterNotNull()
        if (values.distinct().size != values.size) return null
        return values
    }

    fun shouldHandleSilently(action: String?): Boolean =
        action == "android.intent.action.SEND"
}