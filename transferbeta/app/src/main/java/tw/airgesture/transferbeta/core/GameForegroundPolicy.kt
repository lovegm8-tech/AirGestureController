package tw.airgesture.transferbeta.core

/**
 * Game-aware suspension without Usage Access, polling or camera-based detection.
 * Apps recognized by Android as CATEGORY_GAME are supported; known games are
 * included because Android package visibility and work-profile metadata vary.
 */
object GameForegroundPolicy {
    private val knownGames = setOf(
        "com.nianticlabs.pokemongo",        // Pokémon GO - Google Play
        "com.nianticlabs.pokemongo.ares",   // Pokémon GO - Galaxy Store
        "com.nianticlabs.monsterhunter"     // Monster Hunter Now
    )
    // Android ApplicationInfo.CATEGORY_GAME == 0.
    const val CATEGORY_GAME = 0

    fun isGame(packageName: String?, category: Int? = null, legacyGameFlag: Boolean = false): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase(java.util.Locale.ROOT)
        return pkg in knownGames || category == CATEGORY_GAME || legacyGameFlag
    }

    /**
     * Android navigation shade and system overlays are transient; do not
     * resume the camera just because one covers an ongoing game.
     */
    fun isTransientWindow(packageName: String?): Boolean =
        packageName == "com.android.systemui" ||
        packageName == "com.samsung.android.systemui"
}
