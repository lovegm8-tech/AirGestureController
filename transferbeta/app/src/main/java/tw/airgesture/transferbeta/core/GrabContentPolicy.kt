package tw.airgesture.transferbeta.core

import java.net.URI

/** Accessibility text grants neither original photo nor original document access. */
data class VisibleText(val viewId: String?, val text: String?, val password: Boolean = false)

sealed class GrabChoice {
    data class Link(val url: String) : GrabChoice()
    data class ScreenSnapshot(val reason: String) : GrabChoice()
}

object GrabContentPolicy {
    private val browserPackages = setOf(
        "com.android.chrome", "com.sec.android.app.sbrowser", "com.brave.browser",
        "com.microsoft.emmx", "org.mozilla.firefox"
    )
    private val addressIdSuffixes = listOf(
        ":id/url_bar", ":id/location_bar", ":id/location_bar_edit_text",
        ":id/mozac_browser_toolbar_url_view", ":id/url_view"
    )

    fun safeWebUrl(text: String?): String? {
        val s = text?.trim()?.takeIf { it.length in 12..2048 } ?: return null
        if (s.any { it.isWhitespace() || it.code <= 0x1f }) return null
        val parsed = try { URI(s) } catch (_: Exception) { return null }
        if (parsed.scheme?.lowercase() !in setOf("http", "https")) return null
        if (parsed.host.isNullOrBlank() || parsed.userInfo != null) return null
        if (parsed.host.equals("localhost", true)) return null
        return parsed.toASCIIString()
    }

    fun decide(packageName: String?, nodes: List<VisibleText>): GrabChoice {
        if (packageName in browserPackages) {
            for (node in nodes.take(180)) {
                if (node.password) continue
                if (addressIdSuffixes.any { node.viewId?.endsWith(it) == true }) {
                    safeWebUrl(node.text)?.let { return GrabChoice.Link(it) }
                }
            }
        }
        return GrabChoice.ScreenSnapshot(
            "此 App 未提供可驗證網址或具有授權的原檔 URI；僅能擷取畫面"
        )
    }
}
