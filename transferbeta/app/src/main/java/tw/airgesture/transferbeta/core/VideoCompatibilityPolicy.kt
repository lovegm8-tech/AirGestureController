package tw.airgesture.transferbeta.core

enum class VideoSendMode(val key: String, val display: String) {
    AUTO("auto", "自動偵測（建議）"),
    ORIGINAL("original", "只傳原始影片"),
    COMPATIBLE("compatible", "一律優先轉為相容 MP4");
    companion object {
        fun fromKey(value: String?) = entries.firstOrNull { it.key == value } ?: AUTO
    }
}

data class VideoDescription(val mime: String, val width: Int, val height: Int, val hdr: Boolean)

object VideoCompatibilityPolicy {
    fun isRiskyVideo(video: VideoDescription): Boolean =
        video.mime.lowercase() != "video/avc" || video.hdr ||
            video.width > 1920 || video.height > 1920 ||
            (video.width > 1080 && video.height > 1080)

    fun shouldConvert(
        mode: VideoSendMode, video: VideoDescription, receiverCanDecode: Boolean?
    ): Boolean = when (mode) {
        VideoSendMode.ORIGINAL -> false
        VideoSendMode.COMPATIBLE -> true
        VideoSendMode.AUTO -> isRiskyVideo(video) || receiverCanDecode == false
    }

    fun compatibleName(original: String): String {
        val base = (TransferPolicy.safeName(original) ?: "video")
            .substringBeforeLast('.', original).take(92).ifBlank { "video" }
        return "${base}_AirGrab_相容.mp4"
    }
}
