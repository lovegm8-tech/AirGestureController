package tw.airgesture.transferbeta.core

/**
 * A verified SHA-256 proves identical bytes, not playback compatibility.
 * Keep these diagnoses separate so the app never mislabels a codec issue as
 * lost video data, nor guarantees playback from a matching hash.
 */
object VideoPlaybackPolicy {
    fun codecName(mime: String?): String = when (mime?.lowercase()) {
        "video/avc" -> "H.264/AVC"
        "video/hevc" -> "H.265/HEVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4 Visual"
        null -> "未知格式"
        else -> mime
    }

    fun verifiedMessage(videoMime: String?, decoderPresent: Boolean?): String {
        if (videoMime == null)
            return "原檔 SHA-256 驗證通過，但這個檔案沒有可辨識的影片畫面軌道"
        val codec = codecName(videoMime)
        return if (decoderPresent == false)
            "影片原檔完整（${codec}），此裝置未找到相容的影像解碼器；可能有聲無影。可改用 H.264 影片測試"
        else
            "影片原檔完整（${codec}）。若有聲無影，請確認接收端播放器、HDR／10-bit 與編碼相容性"
    }
}
