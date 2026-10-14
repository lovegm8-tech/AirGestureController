package tw.airgesture.controller.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import tw.airgesture.controller.core.GestureDecisionEngine
import java.nio.ByteBuffer

/** Called on one CameraX analysis thread; model inference is entirely on-device. */
class GestureCameraAnalyzer(context: Context, options: GestureDecisionEngine.Options) : AutoCloseable {
    private val decision = GestureDecisionEngine(options)
    private val recognizer: GestureRecognizer
    private var lastFrameMs: Long = 0
    private val inferencePeriodMs = 110L // ~9 fps to reduce battery and thermal load.

    init {
        val base = BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build()
        val config = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(.60f)
            .setMinHandPresenceConfidence(.60f)
            .setMinTrackingConfidence(.60f)
            .build()
        recognizer = GestureRecognizer.createFromOptions(context.applicationContext, config)
    }

    fun process(
        proxy: ImageProxy,
        onAction: (GestureDecisionEngine.Action) -> Unit,
        onPalmDetection: (Boolean) -> Unit
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrameMs < inferencePeriodMs) return
        lastFrameMs = now
        val frame = proxy.toRotatedRgbaBitmap()
        try {
            val result = recognizer.recognizeForVideo(BitmapImageBuilder(frame).build(), now)
            val category = result.gestures().firstOrNull()?.maxByOrNull { it.score() }
            val landmarks = result.landmarks().firstOrNull()
            val shape = when (category?.categoryName()) {
                "Open_Palm" -> GestureDecisionEngine.Shape.OPEN_PALM
                "Closed_Fist" -> GestureDecisionEngine.Shape.CLOSED_FIST
                null -> GestureDecisionEngine.Shape.NO_HAND
                else -> GestureDecisionEngine.Shape.OTHER
            }
            // Wrist + four knuckles; more stable than finger tips while closing a fist.
            val palmY = if (landmarks != null && landmarks.size > 17) {
                listOf(0, 5, 9, 13, 17).map { landmarks[it].y() }.average().toFloat()
            } else null
            val score = category?.score() ?: 0f
            onPalmDetection(shape == GestureDecisionEngine.Shape.OPEN_PALM && score >= .65f)
            decision.accept(GestureDecisionEngine.Frame(shape, palmY, score), now)?.let(onAction)
        } finally {
            frame.recycle()
        }
    }

    override fun close() { recognizer.close() }
}

/** CameraX RGBA_8888, respecting row stride and camera rotation. */
private fun ImageProxy.toRotatedRgbaBitmap(): Bitmap {
    val plane = planes[0]
    val width = width
    val height = height
    val rowBytes = width * 4
    require(plane.pixelStride == 4) { "CameraX analyzer requires RGBA_8888" }
    val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val buffer: ByteBuffer = plane.buffer.duplicate()
    buffer.rewind()
    if (plane.rowStride == rowBytes) {
        source.copyPixelsFromBuffer(buffer)
    } else {
        // Some cameras pad each row; remove padding without assuming contiguous pixels.
        val pixels = ByteArray(rowBytes * height)
        for (row in 0 until height) {
            buffer.position(row * plane.rowStride)
            buffer.get(pixels, row * rowBytes, rowBytes)
        }
        source.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
    }
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return source
    val rotated = Bitmap.createBitmap(source, 0, 0, width, height,
        Matrix().apply { postRotate(rotation.toFloat()) }, true)
    source.recycle()
    return rotated
}
