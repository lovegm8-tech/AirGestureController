package tw.airgesture.transferbeta.core

/**
 * YouTube's in-app sharing panel is distinct from Android's Sharesheet:
 * user grab -> YouTube Share -> Other apps -> system chooser -> AirGrab.
 * Only click a uniquely labelled option while the YouTube share panel
 * is confirmed visible; never guess screen coordinates or read clipboard.
 */
object YouTubeLinkPolicy {
    const val PACKAGE = "com.google.android.youtube"

    private val shareSheetMarkers = setOf(
        "複製連結", "複製鏈接", "复制链接", "copy link",
        "快速分享", "quick share",
        "建立貼文", "建立帖子", "创建帖子", "create post", "create a post"
    )
    private val moreAppsNames = setOf(
        "其他項目", "其他项目", "其他應用程式", "其他应用",
        "更多應用程式", "更多应用", "more apps", "other apps",
        "see all apps", "all apps"
    )

    private fun normalized(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    fun isShareSheet(pkg: String?, visibleText: List<String>): Boolean =
        pkg == PACKAGE && visibleText.any { normalized(it) in shareSheetMarkers }

    fun moreAppsTarget(
        pkg: String?, nodes: List<ActionNode>, visibleText: List<String>
    ): Int? {
        if (!isShareSheet(pkg, visibleText)) return null
        val candidates = nodes.withIndex().filter { (_, node) ->
            node.visible && node.clickable && !node.password &&
                (listOfNotNull(node.label, node.hint) + node.texts)
                    .any { normalized(it) in moreAppsNames }
        }
        return candidates.singleOrNull()?.index
    }
}
