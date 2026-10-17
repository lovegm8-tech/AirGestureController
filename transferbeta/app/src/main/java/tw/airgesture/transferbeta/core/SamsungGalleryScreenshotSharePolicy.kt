package tw.airgesture.transferbeta.core

/**
 * Samsung Gallery sometimes recognizes a URL embedded in a screenshot and
 * offers "螢幕截圖分享形式： / 圖像 / 網頁連結 / 取消".
 * A grab from Gallery means sharing the image, never substituting the URL.
 * This selector is only usable after AirGrab clicked Gallery's own Share.
 */
object SamsungGalleryScreenshotSharePolicy {
    const val GALLERY_PACKAGE = "com.sec.android.gallery3d"

    private val dialogHosts = setOf(
        GALLERY_PACKAGE, "android", "com.samsung.android.app.sharelive",
        "com.samsung.android.app.sharemanager"
    )
    private val dialogTitles = setOf(
        "螢幕截圖分享形式", "屏幕截图分享形式", "螢幕截圖分享方式",
        "screenshot sharing format", "screenshot sharing type"
    )
    private val imageOptions = setOf("圖像", "影像", "图片", "image")
    private val linkOptions = setOf("網頁連結", "网页链接", "webpage link", "web link")

    private fun normalized(input: String): String =
        input.trim().lowercase().trimEnd('：', ':').trim()

    fun isChoiceDialog(
        originPackage: String?, windowPackage: String?, visibleText: List<String>
    ): Boolean {
        if (originPackage != GALLERY_PACKAGE || windowPackage !in dialogHosts) return false
        val lines = visibleText.map(::normalized)
        return lines.any { it in dialogTitles } &&
            lines.any { it in imageOptions } &&
            lines.any { it in linkOptions }
    }

    fun imageTarget(
        originPackage: String?,
        windowPackage: String?,
        visibleText: List<String>,
        nodes: List<ActionNode>
    ): Int? {
        if (!isChoiceDialog(originPackage, windowPackage, visibleText)) return null
        return nodes.withIndex().filter { (_, node) ->
            node.visible && node.clickable && !node.password &&
                (listOfNotNull(node.label, node.hint) + node.texts)
                    .any { normalized(it) in imageOptions }
        }.singleOrNull()?.index
    }
}
