package tw.airgesture.transferbeta.core

/**
 * A physical NFC tap may authorize sharing only the URL of a known browser
 * foreground activity. This is a narrow allowlist, not a generic screen grab.
 *
 * Content acquisition is delegated to each browser's existing bounded,
 * verified accessibility/share flow. Never infer a page from arbitrary text.
 */
object NfcWebSharePolicy {
    const val CHROME = "com.android.chrome"
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    const val SAMSUNG_INTERNET = "com.sec.android.app.sbrowser"

    fun allowsForeground(packageName: String?): Boolean = when (packageName) {
        CHROME, GOOGLE_APP, SAMSUNG_INTERNET -> true
        else -> false
    }

    fun browserName(packageName: String?): String = when (packageName) {
        CHROME -> "Chrome"
        GOOGLE_APP -> "Google App"
        SAMSUNG_INTERNET -> "三星瀏覽器"
        else -> "未知瀏覽器"
    }
}
