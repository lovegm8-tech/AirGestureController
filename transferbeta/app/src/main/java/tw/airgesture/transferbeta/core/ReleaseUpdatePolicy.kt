package tw.airgesture.transferbeta.core

/** Treat FCM as a wake-up hint; only official GitHub Releases are trusted. */
object ReleaseUpdatePolicy {
    const val REPOSITORY = "lovegm8-tech/AirGestureController"
    const val RELEASES_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=15"
    const val ASSET = "app-release.apk"
    const val MAX_APK_BYTES = 180L * 1024L * 1024L
    const val CHECK_INTERVAL_HOURS = 24L

    enum class Channel(val topic: String) {
        TEST("airgesture-test"), STABLE("airgesture-stable")
    }

    private val tagPattern = Regex("^v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})(?:-beta\\.(\\d{1,3}))?$")

    fun isNewer(tag: String, installed: String): Boolean {
        val remote = parse(tag) ?: return false
        val local = parse(installed) ?: return false
        for (i in 0..2) {
            if (remote[i] != local[i]) return remote[i] > local[i]
        }
        // For same numeric version, stable is newer than beta.
        val remoteBeta = remote[3]
        val localBeta = local[3]
        return if (remoteBeta == -1) localBeta != -1
            else localBeta != -1 && remoteBeta > localBeta
    }

    private fun parse(tag: String): List<Int>? {
        val match = tagPattern.matchEntire(tag) ?: return null
        return match.groupValues.drop(1).mapIndexed { index, v ->
            if (index == 3 && v.isBlank()) -1 else v.toInt()
        }
    }

    fun correctChannel(tag: String, prerelease: Boolean, channel: Channel): Boolean {
        if (parse(tag) == null) return false
        return if (channel == Channel.TEST) prerelease && "-beta." in tag
            else !prerelease && "-beta." !in tag
    }

    fun acceptableAsset(name: String, url: String, digest: String, bytes: Long): Boolean =
        name == ASSET && url.startsWith(
            "https://github.com/$REPOSITORY/releases/download/") &&
            Regex("^sha256:[a-fA-F0-9]{64}$").matches(digest) &&
            bytes in 1_000_000L..MAX_APK_BYTES

    fun trustedDownloadHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "github.com" ||
            h == "release-assets.githubusercontent.com" ||
            h == "objects.githubusercontent.com" ||
            h == "github-releases.githubusercontent.com"
    }
}
