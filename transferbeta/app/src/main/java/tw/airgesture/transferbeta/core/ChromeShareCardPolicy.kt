package tw.airgesture.transferbeta.core

/** A bounded fallback for Chrome's current-page card in the address editor.
 * Only use after a user-initiated tap of Chrome's address bar. Some Chrome
 * builds expose the Share icon without an accessibility label, but expose
 * Copy and Edit icons beside it. No web-page coordinates are ever accepted.
 */
data class ChromeCardControl(
    val label: String,
    val x: Float,
    val y: Float,
    val visible: Boolean = true,
    val clickable: Boolean = true
)
data class ChromeCardPoint(val x: Float, val y: Float)

object ChromeShareCardPolicy {
    /**
     * If Chrome opens the address search editor and Samsung IME becomes the
     * active accessibility window, only the unique underlying Chrome app
     * window may be consulted. Never search other foreground applications.
     */
    fun mayUseChromeWindowBehindKeyboard(
        requestedPackage: String?,
        activePackage: String?,
        keyboardWindowVisible: Boolean,
        chromeWindowCount: Int
    ): Boolean =
        requestedPackage == "com.android.chrome" &&
            activePackage != requestedPackage &&
            keyboardWindowVisible && chromeWindowCount == 1

    fun inferSharePoint(
        appPackage: String?,
        editorWasOpenedByAirGrab: Boolean,
        screenWidth: Float,
        screenHeight: Float,
        controls: List<ChromeCardControl>
    ): ChromeCardPoint? {
        if (appPackage != "com.android.chrome" || !editorWasOpenedByAirGrab ||
            screenWidth <= 0f || screenHeight <= 0f) return null
        val usable = controls.filter { it.visible && it.clickable &&
            it.y in (screenHeight * .095f)..(screenHeight * .23f) }
        val copyLabels = setOf("copy", "copy link", "複製", "複製連結", "复制", "复制链接")
        val editLabels = setOf("edit", "edit link", "編輯", "编辑")
        val copy = usable.filter { it.label.trim().lowercase() in copyLabels }
            .singleOrNull() ?: return null
        val edit = usable.filter { it.label.trim().lowercase() in editLabels }
            .singleOrNull() ?: return null
        // Screenshot-backed layout: current page's Share, Copy, Edit icons
        // appear in one row in this order near the top right. Never infer a
        // share button from arbitrary page text or a context menu.
        if (copy.x !in (screenWidth * .64f)..(screenWidth * .87f) ||
            edit.x !in (screenWidth * .80f)..(screenWidth * .99f) ||
            edit.x <= copy.x || kotlin.math.abs(edit.y - copy.y) > screenHeight * .025f)
            return null
        val separation = edit.x - copy.x
        if (separation !in (screenWidth * .06f)..(screenWidth * .17f)) return null
        val x = copy.x - separation
        if (x !in (screenWidth * .53f)..(screenWidth * .78f)) return null
        return ChromeCardPoint(x, (copy.y + edit.y) / 2f)
    }
}
