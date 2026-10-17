package tw.airgesture.transferbeta.core

/** TikTok's own bottom sheet is not an Android Sharesheet.
 * User grab -> TikTok Share -> horizontally browse apps -> More -> Android
 * chooser -> AirGrab ACTION_SEND (video URL; not video bytes).
 * Never select "More" from the video caption or other application windows.
 */
data class TikTokShareCandidate(val node: ActionNode, val centerYRatio: Float)

object TikTokSharePolicy {
    private val packages = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    private val sheetTitles = setOf(
        "傳送至", "传送至", "傳送給", "发送给", "send to", "share to"
    )
    private val moreLabels = setOf(
        "更多", "more", "更多應用程式", "更多应用", "more apps", "other apps"
    )

    fun isTikTok(pkg: String?): Boolean = pkg in packages
    private fun norm(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    fun isNativeShareSheet(pkg: String?, visibleText: List<String>): Boolean =
        isTikTok(pkg) && visibleText.any { norm(it) in sheetTitles }

    fun moreAppsTarget(
        pkg: String?,
        visibleText: List<String>,
        buttons: List<TikTokShareCandidate>
    ): Int? {
        if (!isNativeShareSheet(pkg, visibleText)) return null
        return buttons.withIndex().filter { (_, candidate) ->
            val n = candidate.node
            n.visible && n.clickable && !n.password &&
                candidate.centerYRatio in 0.70f..0.89f &&
                (listOfNotNull(n.label, n.hint) + n.texts)
                    .any { norm(it) in moreLabels }
        }.singleOrNull()?.index
    }
}
