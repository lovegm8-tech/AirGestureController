package tw.airgesture.transferbeta

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import tw.airgesture.transferbeta.core.VideoDescription
import java.io.File

/** Hardware-assisted H.264/AAC MP4 output, with HDR->SDR tonemapping.
 * The original stays untouched. Do not send the file until onCompleted fires.
 */
@UnstableApi
class CompatibilityVideoTranscoder(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var output: File? = null
    private var finished = false

    fun export(
        input: File, video: VideoDescription,
        onComplete: (File) -> Unit, onFailure: (String) -> Unit
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Transformer must be created on the main thread"
        }
        if (transformer != null) {
            onFailure("已有影片正在轉檔")
            return
        }
        try {
            val dir = File(context.cacheDir, "video-compat").apply { mkdirs() }
            dir.listFiles()?.filter {
                it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000L
            }?.forEach { it.delete() }
            val dest = File.createTempFile("airgrab-compatible-", ".mp4", dir)
            dest.delete() // Transformer requires a non-existing output path.
            output = dest
            finished = false
            val item = MediaItem.Builder().setUri(Uri.fromFile(input))
                .setMimeType(MimeTypes.VIDEO_MP4).build()
            val builder = EditedMediaItem.Builder(item)
            if (video.width > 1920 || video.height > 1920 ||
                (video.width > 1080 && video.height > 1080)) {
                builder.setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(1080))))
            }
            val edited = builder.build()
            val composition = Composition.Builder(
                EditedMediaItemSequence(edited))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            val instance = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (finished) return
                        finished = true
                        transformer = null
                        if (dest.isFile && dest.length() > 0) onComplete(dest)
                        else {
                            dest.delete()
                            onFailure("轉檔後沒有產生有效檔案")
                        }
                    }
                    override fun onError(
                        composition: Composition, exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (finished) return
                        finished = true
                        transformer = null
                        dest.delete()
                        onFailure(exportException.message ?: "裝置不支援這部影片的轉檔")
                    }
                })
                .build()
            transformer = instance
            instance.start(composition, dest.absolutePath)
        } catch (e: Exception) {
            finished = true
            transformer = null
            output?.delete()
            onFailure(e.message ?: "轉檔功能啟動失敗")
        }
    }

    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancel() }
            return
        }
        finished = true
        try { transformer?.cancel() } catch (_: Exception) {}
        transformer = null
        output?.delete()
        output = null
    }
}
