package tw.airgesture.transferbeta.subtitle

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * English-only streaming recognizer. Receives the *same* playback PCM frames
 * as SenseVoice; it never opens another AudioRecord or microphone.
 * Model files must be installed in private storage before start().
 */
class SubtitleZipformerSpeech(
    private val modelFolder: File,
    private val callback: SubtitlePlaybackSpeech.Callback
) : SubtitleSenseVoiceSpeech.PcmObserver {
    private val frames = ArrayBlockingQueue<ByteArray>(32)
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "subtitle-zipformer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    override fun onPcm16kMono(pcm: ByteArray) {
        if (!running.get()) return
        if (!frames.offer(pcm.copyOf())) {
            frames.clear() // Drop stale speech rather than building subtitle lag.
            frames.offer(pcm.copyOf())
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker.execute(::recognizeLoop)
    }

    fun stop() {
        running.set(false)
        worker.shutdown()
    }

    private fun recognizeLoop() {
        var recognizer: OnlineRecognizer? = null
        try {
            val encoder = File(modelFolder, "encoder.onnx")
            val decoder = File(modelFolder, "decoder.onnx")
            val joiner = File(modelFolder, "joiner.onnx")
            val tokens = File(modelFolder, "tokens.txt")
            require(listOf(encoder, decoder, joiner, tokens).all { it.isFile && it.length() > 0 }) {
                "Zipformer model files missing"
            }
            recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu"
                )
            ))
            var stream = recognizer.createStream()
            var lastText = ""
            var utteranceId = 1L
            var lastPrefetchNs = 0L
            var lastPrefetchText = ""
            var lastDeliveredText = ""
            var lastDeliveredNs = 0L
            callback.onStatus("Zipformer 英文串流辨識已啟動")
            try {
                while (running.get() || frames.isNotEmpty()) {
                    val raw = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val samples = FloatArray(raw.size / 2) { i ->
                        val lo = raw[2 * i].toInt() and 255
                        val hi = raw[2 * i + 1].toInt()
                        ((lo or (hi shl 8)).toShort().toFloat()) / 32768f
                    }
                    stream.acceptWaveform(samples, 16_000)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val current = recognizer.getResult(stream).text.trim()
                    if (current.isNotEmpty() && current != lastText) {
                        callback.onHypothesis(current, false)
                        // Rate-limit speculative ML Kit requests. Every decoder
                        // hypothesis still reaches diagnostics, not translation.
                        val nowNs = System.nanoTime()
                        val normalized = current.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
                        val changedEnough = normalized != lastPrefetchText &&
                            (lastPrefetchText.isEmpty() || !normalized.startsWith(lastPrefetchText) ||
                                normalized.length - lastPrefetchText.length >= 18)
                        val recentDuplicate = normalized == lastDeliveredText &&
                            nowNs - lastDeliveredNs < 8_000_000_000L
                        if (current.length >= 12 && changedEnough && !recentDuplicate &&
                            (lastPrefetchNs == 0L || nowNs - lastPrefetchNs >= 1_200_000_000L)) {
                            callback.onUtterance(current, utteranceId, false)
                            lastPrefetchNs = nowNs
                            lastPrefetchText = normalized
                            lastDeliveredText = normalized
                            lastDeliveredNs = nowNs
                        }
                        lastText = current
                    }
                    if (recognizer.isEndpoint(stream)) {
                        recognizer.reset(stream)
                        lastText = ""
                        utteranceId++
                        lastPrefetchNs = 0L
                        lastPrefetchText = ""
                    }
                }
            } finally {
                stream.release()
            }
        } catch (error: Throwable) {
            callback.onStatus("Zipformer 無法啟動：" + error.javaClass.simpleName + "；SenseVoice 可繼續運作")
        } finally {
            running.set(false)
            recognizer?.release()
        }
    }
}
