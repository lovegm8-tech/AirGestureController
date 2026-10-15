package tw.airgesture.transferbeta.core

/** Pure transport envelope validation. JSON parsing and Nearby are adapters. */
object TransferPolicy {
    const val MAX_BYTES = 256L * 1024L * 1024L
    const val OFFER_TIMEOUT_MS = 30_000L
    fun safeName(raw: String?): String? {
        val str = raw?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.replace(Regex("[\\x00-\\x1f]"), "_")?.trim()?.take(120)
        return str?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }
    fun validFileOffer(session: String, rawName: String?, count: Long, sha: String): Boolean =
        session.length in 12..100 && safeName(rawName) != null &&
            count in 0..MAX_BYTES && Regex("[0-9a-f]{64}").matches(sha)

    fun validLinkOffer(session: String, url: String): Boolean =
        session.length in 12..100 && GrabContentPolicy.safeWebUrl(url) == url

    fun matchingHash(expected: String, actual: String, expectedSize: Long, actualSize: Long): Boolean =
        expected == actual && expectedSize == actualSize && expected.length == 64
}
