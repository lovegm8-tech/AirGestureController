package tw.airgesture.transferbeta

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import tw.airgesture.transferbeta.core.VideoPlaybackPolicy

/**
 * Playback diagnostic only. Does not decode or recompress the movie. Transport
 * integrity remains solely determined by original byte count and SHA-256.
 */
object VideoPlaybackProbe {
    fun explain(context: Context, uri: Uri): String {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var videoFormat: MediaFormat? = null
            var videoMime: String? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoFormat = format
                    videoMime = mime
                    break
                }
            }
            val decoder = videoFormat?.let { format ->
                try {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS)
                        .findDecoderForFormat(format) != null
                } catch (_: Exception) {
                    null
                }
            }
            VideoPlaybackPolicy.verifiedMessage(videoMime, decoder)
        } catch (_: Exception) {
            "影片原檔 SHA-256 驗證通過，但此裝置無法分析影像編碼；請嘗試其他播放器"
        } finally {
            extractor.release()
        }
    }
}
