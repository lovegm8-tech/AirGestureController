package tw.airgesture.transferbeta.core

/**
 * Platform-independent part of automated share handling.
 * A grab gesture only authorizes the *current* foreground item, never a sweep
 * of unrelated screens, and never changes cloud permissions.
 */
enum class GrabKind { PHOTO, FILE, PDF, OFFICE, CLOUD_DOCUMENT, BROWSER, VIDEO, SOCIAL, UNKNOWN }
enum class WantedResult { FILE, LINK, EITHER }

data class ShareStep(
    val tokens: List<String>,
    val preferredIds: List<String> = emptyList()
)

data class SharePlan(
    val kind: GrabKind,
    val expected: WantedResult,
    val steps: List<ShareStep>,
    val allowChooserSelection: Boolean = true,
    val description: String
)

data class ActionNode(
    val id: String?,
    val label: String?,
    val hint: String? = null,
    val clickable: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
    val depth: Int = 0
)

object ShareAutomationPolicy {
    private val shareWords = listOf("分享", "share")
    private val sendWords = listOf("傳送副本", "傳送檔案", "send a copy", "send file", "share a copy")
    private val moreWords = listOf("更多選項", "更多", "more options", "more")
    private val exportWords = listOf("共用與匯出", "分享與匯出", "share & export", "share and export")
    private fun step(words: List<String>, vararg ids: String) =
        ShareStep(words, ids.toList())

    fun plan(pkg: String?): SharePlan? = when(pkg) {
        "com.android.chrome", "com.sec.android.app.sbrowser",
        "com.brave.browser", "com.microsoft.emmx", "org.mozilla.firefox",
        "com.google.android.googlequicksearchbox" ->
            SharePlan(GrabKind.BROWSER, WantedResult.LINK,
                listOf(step(shareWords, ":id/share", ":id/action_share")),
                description = "網址欄不提供完整網址時，改由瀏覽器分享")
        "com.sec.android.gallery3d", "com.google.android.apps.photos" ->
            SharePlan(GrabKind.PHOTO, WantedResult.FILE,
                listOf(step(shareWords, ":id/share", ":id/action_share")),
                description = "使用相簿分享原照片／原圖副本")
        "com.sec.android.app.myfiles", "com.google.android.documentsui" ->
            SharePlan(GrabKind.FILE, WantedResult.FILE,
                listOf(step(shareWords, ":id/share")),
                description = "以檔案管理員分享已選取的檔案")
        "com.adobe.reader", "com.google.android.apps.pdfviewer" ->
            SharePlan(GrabKind.PDF, WantedResult.FILE,
                listOf(step(shareWords + sendWords, ":id/share")),
                description = "由 PDF 閱讀器分享文件副本")
        "com.microsoft.office.word", "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint", "com.microsoft.office.officehubrow" ->
            SharePlan(GrabKind.OFFICE, WantedResult.FILE,
                listOf(step(shareWords + sendWords, ":id/share")),
                description = "Office 優先分享副本，未提供就請使用者手動選擇")
        "com.google.android.apps.docs.editors.docs",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides" ->
            SharePlan(GrabKind.CLOUD_DOCUMENT, WantedResult.EITHER,
                listOf(step(exportWords + shareWords + moreWords),
                       step(sendWords + shareWords)),
                description = "Google 文件匯出 Office 副本；若格式選單不確定則停止自動化")
        "com.google.android.youtube", "com.google.android.apps.youtube.music",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.facebook.katana", "com.instagram.android" ->
            SharePlan(if (pkg.startsWith("com.google.android.youtube")) GrabKind.VIDEO else GrabKind.SOCIAL,
                WantedResult.LINK,
                listOf(step(shareWords, ":id/share_button", ":id/share")),
                description = "使用影片／貼文既有分享功能取得官方連結")
        else -> null
    }

    /** Browser share controls are usually inside a toolbar overflow menu, not
     * directly on the page. Match known menu controls only; never guess XY. */
    fun browserOverflow(nodes: List<ActionNode>): Int? {
        val labels = setOf("更多", "更多選項", "選單", "主選單", "more",
            "more options", "menu", "open menu", "customize and control google chrome")
        val ids = listOf(":id/menu_button", ":id/menu_btn", ":id/action_menu",
            ":id/overflow_menu", ":id/menu_button_wrapper", ":id/toolbar_menu_button")
        return nodes.withIndex().filter { (_, n) ->
            n.visible && n.clickable && !n.password &&
                (ids.any { n.id?.lowercase()?.endsWith(it) == true } ||
                 listOfNotNull(n.label, n.hint).any { it.trim().lowercase() in labels })
        }.singleOrNull()?.index
    }

    fun match(step: ShareStep, nodes: List<ActionNode>): Int? {
        val matches = nodes.withIndex().filter { (_, node) ->
            node.visible && node.clickable && !node.password &&
                (
                    step.preferredIds.any { suffix ->
                        node.id?.lowercase()?.endsWith(suffix.lowercase()) == true
                    } || listOfNotNull(node.label, node.hint).any { text ->
                        val label = text.trim().lowercase()
                        step.tokens.any { key -> label == key || label.startsWith("$key ") }
                    }
                )
        }
        // Unambiguous selection only; some screens show a share for every list item.
        return matches.singleOrNull()?.index
    }

    fun isTrustedResolver(pkg: String?): Boolean = pkg in setOf(
        "android", "com.android.intentresolver", "com.google.android.intentresolver",
        "com.samsung.android.app.sharelive", "com.samsung.android.app.sharemanager"
    )

    fun onlyUniqueChooserTarget(pkg: String?, nodes: List<ActionNode>): Int? {
        if (!isTrustedResolver(pkg)) return null
        val appLabels = setOf("AirGrab", "AirGrab 隔空抓取", "隔空傳檔測試版")
        val matches = nodes.withIndex().filter { (_, n) ->
            n.visible && n.clickable && !n.password &&
                ((n.label?.trim() in appLabels) || (n.hint?.trim() in appLabels))
        }
        return matches.singleOrNull()?.index
    }
}
