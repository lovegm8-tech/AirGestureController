package tw.airgesture.transferbeta.core

import java.net.URI

/**
 * Screenshot-confirmed YouTube share sheet: Share -> Copy link.
 * Never guesses a URL from a video title, reads arbitrary clipboard text,
 * or accepts a stale link copied before the current grab gesture.
 */
object YouTubeLinkPolicy {
    const val PACKAGE = "com.google.android.youtube"

    private val copyLabels = setOf(
        "複製連結", "複製鏈接", "复制链接", "copy link", "copy url"
    )
    private val sheetMarkers = setOf(
        "快速分享", "建立貼文", "建立帖子", "创建帖子",
        "quick share", "create post", "create a post"
    )

    fun copyLinkTarget(
        windowPackage: String?,
        nodes: List<ActionNode>,
        visibleText: List<String>
    ): Int? {
        if (windowPackage != PACKAGE) return null
        // Distinguish YouTube's native sheet from a button in the video page.
        if (visibleText.none { it.trim().lowercase() in sheetMarkers }) return null
        return nodes.withIndex().filter { (_, node) ->
            node.visible && node.clickable && !node.password &&
                (listOfNotNull(node.label, node.hint) + node.texts)
                    .any { it.trim().lowercase() in copyLabels }
        }.singleOrNull()?.index
    }

    fun freshVideoLink(
        rawClipboard: String?,
        clickedAtMillis: Long,
        clipboardTimestampMillis: Long,
        nowMillis: Long
    ): String? {
        if (clickedAtMillis <= 0L || clipboardTimestampMillis <= 0L ||
            nowMillis < clickedAtMillis || nowMillis - clickedAtMillis > 12_000L ||
            clipboardTimestampMillis < clickedAtMillis - 500L ||
            clipboardTimestampMillis > nowMillis + 2_000L) return null

        val url = SharedTextPolicy.onlyWebLink(rawClipboard) ?: return null
        val parsed = try { URI(url) } catch (_: Exception) { return null }
        val host = parsed.host?.lowercase() ?: return null
        val path = parsed.rawPath ?: ""
        if (host == "youtu.be") return url.takeIf { path.length > 1 }
        if (host !in setOf(
                "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"
            )) return null
        val validVideoPath = (path == "/watch" &&
            !parsed.rawQuery.isNullOrEmpty() &&
            Regex("(?:^|&)v=[A-Za-z0-9_-]+(?:&|$)").containsMatchIn(parsed.rawQuery!!)) ||
            listOf("/shorts/", "/live/", "/clip/").any {
                path.startsWith(it) && path.length > it.length
            }
        return url.takeIf { validVideoPath }
    }
}
