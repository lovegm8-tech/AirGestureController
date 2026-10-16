package tw.airgesture.transferbeta

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import tw.airgesture.transferbeta.core.VideoDescription
import java.io.File

/** Reads metadata only. It never decodes or modifies the original file. */
object VideoFormatInspector {
    fun inspect(file: File): VideoDescription? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val width = if (format.containsKey(MediaFormat.KEY_WIDTH))
                    format.getInteger(MediaFormat.KEY_WIDTH) else 0
                val height = if (format.containsKey(MediaFormat.KEY_HEIGHT))
                    format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
                    format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                val hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                    transfer == MediaFormat.COLOR_TRANSFER_HLG
                return VideoDescription(mime, width, height, hdr)
            }
        } catch (_: Exception) {
            // Uninspectable file is sent as original, never renamed to a fake MP4.
        } finally {
            extractor.release()
        }
        return null
    }

    fun receiverCanDecode(description: VideoDescription): Boolean {
        if (description.width < 1 || description.height < 1) return false
        return try {
            val format = MediaFormat.createVideoFormat(
                description.mime, description.width, description.height)
            // Advertised decoder support is not proof of 10-bit/HDR playback.
            val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(format)
            decoder != null && !description.hdr
        } catch (_: Exception) { false }
    }
}
