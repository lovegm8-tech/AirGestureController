package tw.airgesture.transferbeta.subtitle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SenseVoice comparison engine. Playback audio capture is identical in
 * permission/scope to the Vosk path. A single offline inference thread
 * consumes bounded 16-kHz PCM; there is no microphone or cloud ASR.
 *
 * v1: strictly non-overlapping 2.8-second chunks. This avoids replaying
 * previous words, and provides a measurable latency/accuracy baseline.
 */
class SubtitleSenseVoiceSpeech(
    context: Context,
    private val projection: MediaProjection,
    private val language: String,
    private val callback: SubtitlePlaybackSpeech.Callback,
    private val pcmObserver: PcmObserver? = null
) {
    /** Non-blocking PCM fan-out hook for a future streaming recognizer.
     * Called on the capture thread; implementations must copy the buffer if retained. */
    fun interface PcmObserver {
        fun onPcm16kMono(pcm: ByteArray)
    }

    private val app = context.applicationContext
    private val alive = AtomicBoolean(false)
    private val captureDone = AtomicBoolean(false)
    @Volatile private var stopDeadlineMs = Long.MAX_VALUE
    private val queue = ArrayBlockingQueue<ByteArray>(80)
    private val ready = CountDownLatch(1)
    private val executor = Executors.newFixedThreadPool(2) { job ->
        Thread(job, "subtitle-sensevoice").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    @Volatile private var capture: AudioRecord? = null
    @Volatile private var failed = false

    fun start() {
        if (!SubtitleSenseVoiceModel.installed(app)) {
            callback.onStatus("SenseVoice 模型未安裝，請先下載安裝；OCR 仍可使用")
            return
        }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callback.onStatus("SenseVoice 未授權播放音訊擷取")
            return
        }
        if (!alive.compareAndSet(false, true)) return
        captureDone.set(false)
        stopDeadlineMs = Long.MAX_VALUE
        executor.execute(::decodeLoop)
        executor.execute(::captureLoop)
    }

    fun stop() {
        stopDeadlineMs = SystemClock.elapsedRealtime() + 1_500L
        alive.set(false)
        capture?.let { try { it.stop() } catch (_: Exception) {} }
        ready.countDown()
        // Let the decoder drain queued PCM and flush the final partial window.
        // Interrupting it here would silently discard the last spoken words.
        executor.shutdown()
    }

    private fun decodeLoop() {
        var asr: OfflineRecognizer? = null
        try {
            callback.onStatus("SenseVoice 離線模型載入中（約 226 MB，CPU）")
            val folder: File = SubtitleSenseVoiceModel.modelFolder(app)
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(folder, "model.int8.onnx").absolutePath,
                        language = language,
                        useInverseTextNormalization = true
                    ),
                    tokens = File(folder, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu"
                )
            )
            asr = OfflineRecognizer(config = config)
            callback.onStatus("SenseVoice 模型已載入，等待允許擷取的影片音訊")
            ready.countDown()
            val audio = ArrayList<Float>(64_000)
            var quietSamples = 0
            var voicedSamples = 0
            var utteranceId = 0L
            while ((alive.get() || !captureDone.get() || queue.isNotEmpty()) &&
                !Thread.currentThread().isInterrupted &&
                SystemClock.elapsedRealtime() < stopDeadlineMs) {
                val raw = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                for (i in 0 until raw.size / 2) {
                    val lo = raw[i * 2].toInt() and 0xff
                    val hi = raw[i * 2 + 1].toInt()
                    val signed = (lo or (hi shl 8)).toShort()
                    audio.add(signed.toFloat() / 32768f)
                    if (kotlin.math.abs(signed.toInt()) > 650) { voicedSamples++; quietSamples = 0 } else quietSamples++
                }
                // End at a natural pause when possible, with a bounded latency fallback.
                // RMS/energy is deliberately a lightweight endpoint heuristic,
                // not a full neural VAD; silence-only windows are discarded.
                val naturalPause = quietSamples >= 8_000 && audio.size >= 12_800
                val maxWindow = audio.size >= 38_400
                if (!naturalPause && !maxWindow) continue
                if (voicedSamples < 1_600) {
                    audio.clear(); quietSamples = 0; voicedSamples = 0
                    continue
                }
                val samples = FloatArray(audio.size) { audio[it] }
                audio.clear()  // no overlapping window, no repeated transcript
                quietSamples = 0
                voicedSamples = 0
                val started = SystemClock.elapsedRealtime()
                val stream = asr.createStream()
                try {
                    stream.acceptWaveform(samples, 16_000)
                    asr.decode(stream)
                    val original = asr.getResult(stream).text
                        .replace(Regex("<\\|[^>]+\\|>"), "")
                        .replace(Regex("\\s+"), " ").trim()
                    val cost = SystemClock.elapsedRealtime() - started
                    if (original.isNotBlank()) {
                        utteranceId++
                        callback.onHypothesis(original, true)
                        callback.onUtterance(original, utteranceId, true)
                        callback.onStatus("SenseVoice 辨識完成：推論 ${cost}ms，語音片段依停頓或 3 秒上限")
                    }
                } finally {
                    stream.release()
                }
                // Avoid interpreting seconds-old audio when the CPU is overloaded.
                if (queue.size > 50) {
                    queue.clear()
                    callback.onStatus("SenseVoice 推論速度跟不上影片，已丟棄過期音訊（防止字幕積壓）")
                }
            }
            // A user stop must not discard the final 0.1–2.9 seconds.
            // Ignore tiny or nearly silent tails, and decode at most one final
            // partial window using the same offline model and callbacks.
            if (captureDone.get() && audio.size >= 1_600 && voicedSamples >= 1_600 &&
                SystemClock.elapsedRealtime() < stopDeadlineMs) {
                val tail = FloatArray(audio.size) { audio[it] }
                val stream = asr.createStream()
                try {
                    stream.acceptWaveform(tail, 16_000)
                    asr.decode(stream)
                    val original = asr.getResult(stream).text
                        .replace(Regex("<\\|[^>]+\\|>"), "")
                        .replace(Regex("\\s+"), " ").trim()
                    if (original.isNotBlank()) {
                        utteranceId++
                        callback.onHypothesis(original, true)
                        callback.onUtterance(original, utteranceId, true)
                        callback.onStatus("SenseVoice 已處理停止時剩餘音訊")
                    }
                } finally {
                    stream.release()
                }
            }
        } catch (error: Throwable) {
            failed = true
            callback.onStatus("SenseVoice 無法啟動：" + (error.javaClass.simpleName + ": " + (error.message ?: error.cause?.message ?: "no detail").take(240)) + "；使用 OCR 備援")
        } finally {
            ready.countDown()
            try { asr?.release() } catch (_: Throwable) {}
            alive.set(false)
        }
    }

    private fun captureLoop() {
        var record: AudioRecord? = null
        try {
            if (!ready.await(30, TimeUnit.SECONDS) || failed || !alive.get()) return
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME).build()
            val min = AudioRecord.getMinBufferSize(16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(min > 0) { "16 kHz playback capture unavailable" }
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(8192, min * 2))
                .setAudioPlaybackCaptureConfig(cfg).build()
            require(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
            capture = record
            record.startRecording()
            require(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord not capturing"
            }
            callback.onStatus("SenseVoice 播放音訊擷取正常（未使用麥克風）")
            val pcm = ByteArray(3200)
            while (alive.get() && !Thread.currentThread().isInterrupted) {
                val n = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                if (n == 0) continue
                val frame = pcm.copyOf(n)
                // Feed the same captured frame to both ASR engines. A slow or
                // failing observer must never block playback capture.
                if (pcmObserver != null) {
                    try { pcmObserver.onPcm16kMono(frame) }
                    catch (_: Exception) { /* SenseVoice remains available */ }
                }
                if (!queue.offer(frame)) {
                    queue.clear()
                    queue.offer(frame)
                    callback.onStatus("SenseVoice 音訊佇列已清空，避免長時間字幕落後")
                }
            }
        } catch (error: Exception) {
            if (alive.get()) callback.onStatus("SenseVoice 無法擷取播放音訊：" + (error.javaClass.simpleName + ": " + (error.message ?: error.cause?.message ?: "no detail").take(240)))
        } finally {
            capture = null
            if (record != null) {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
            alive.set(false)
            captureDone.set(true)
        }
    }
}
