package tw.airgesture.transferbeta.subtitle

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Japanese ReazonSpeech INT8 offline transducer; receives shared playback PCM. */
class SubtitleJapaneseSpeech(
    private val modelFolder: File,
    private val callback: SubtitlePlaybackSpeech.Callback
) : SubtitleSenseVoiceSpeech.PcmObserver {
    private val frames = ArrayBlockingQueue<ByteArray>(64)
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "subtitle-reazonspeech").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    override fun onPcm16kMono(pcm: ByteArray) {
        if (!running.get()) return
        if (!frames.offer(pcm.copyOf())) {
            frames.poll() // Drop only the oldest chunk to limit audio loss.
            frames.offer(pcm.copyOf())
        }
    }

    fun start() {
        if (running.compareAndSet(false, true)) worker.execute(::decodeLoop)
    }

    fun stop() {
        running.set(false)
        frames.clear()
        worker.shutdown()
    }

    private fun decodeLoop() {
        var recognizer: OfflineRecognizer? = null
        try {
            val encoder = File(modelFolder, "encoder.onnx")
            val decoder = File(modelFolder, "decoder.onnx")
            val joiner = File(modelFolder, "joiner.onnx")
            val tokens = File(modelFolder, "tokens.txt")
            require(listOf(encoder, decoder, joiner, tokens).all { it.isFile && it.length() > 0 })
            recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu"
                )
            ))
            callback.onStatus("ReazonSpeech 日語離線辨識已啟動")
            val audio = ArrayList<Float>(48_000)
            var id = 0L
            while (running.get()) {
                val pcm = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                for (i in 0 until pcm.size / 2) {
                    val lo = pcm[i * 2].toInt() and 255
                    val hi = pcm[i * 2 + 1].toInt()
                    audio.add((lo or (hi shl 8)).toShort().toFloat() / 32768f)
                }
                if (audio.size < 40_000) continue
                // Bound offline chunks to ~2.5 seconds to avoid growing memory
                // while the recognizer processes speech on a busy device.
                val samples = FloatArray(audio.size) { audio[it] }
                audio.clear()
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, 16_000)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream).text.trim()
                    if (result.isNotBlank()) {
                        id++
                        callback.onHypothesis(result, false)
                        callback.onUtterance(result, id, false)
                    }
                } finally {
                    stream.release()
                }
            }
        } catch (error: Throwable) {
            callback.onStatus("ReazonSpeech 無法啟動：" + (error.javaClass.simpleName + ": " + (error.message ?: error.cause?.message ?: "no detail").take(240)) + "；SenseVoice 繼續運作")
        } finally {
            running.set(false)
            recognizer?.release()
        }
    }
}
