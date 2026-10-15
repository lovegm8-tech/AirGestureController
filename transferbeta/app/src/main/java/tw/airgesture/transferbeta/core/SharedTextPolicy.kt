package tw.airgesture.transferbeta.core

/** Only transmit explicit single http(s) URI from Android Sharesheet text. */
object SharedTextPolicy {
    private val urlPattern = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    fun onlyWebLink(shared: String?): String? {
        val text = shared?.takeIf { it.length <= 4096 } ?: return null
        return urlPattern.findAll(text).mapNotNull {
            GrabContentPolicy.safeWebUrl(
                it.value.trimEnd('.', ',', '，', '。', ')', '）'))
        }.distinct().take(2).toList().singleOrNull()
    }
}
