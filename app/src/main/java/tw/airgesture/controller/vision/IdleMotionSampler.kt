package tw.airgesture.controller.vision

import androidx.camera.core.ImageProxy
import tw.airgesture.controller.core.IdleMotionDetector

/**
 * Reads 192 small RGBA samples every 180ms in deep idle, without converting
 * a Bitmap or invoking MediaPipe. No camera frames are kept or uploaded.
 *
 * Only triggers a fresh AI verification; it can never trigger a touch action.
 */
internal class IdleMotionSampler {
    private val detector = IdleMotionDetector()
    private var previousSampleTime = Long.MIN_VALUE
    private val samples = IntArray(16 * 12)

    fun reset() {
        if (previousSampleTime != Long.MIN_VALUE) {
            detector.reset()
            previousSampleTime = Long.MIN_VALUE
        }
    }

    fun observe(proxy: ImageProxy, nowMs: Long): Boolean {
        if (previousSampleTime != Long.MIN_VALUE &&
            nowMs >= previousSampleTime && nowMs - previousSampleTime < 180L) return false
        if (previousSampleTime != Long.MIN_VALUE && nowMs < previousSampleTime) reset()
        previousSampleTime = nowMs
        if (proxy.planes.isEmpty() || proxy.width < 16 || proxy.height < 12) return false
        val plane = proxy.planes[0]
        if (plane.pixelStride != 4) return false
        val buf = plane.buffer
        val h = proxy.height
        val w = proxy.width
        var n = 0
        // Each sample is from the central 80% of the image. The 1.5s
        // mandatory AI scan remains active for gestures at the frame edges.
        for (row in 0 until 12) {
            val y = h * (row * 4 + 6) / (12 * 4 + 12)
            for (col in 0 until 16) {
                val x = w * (col * 4 + 6) / (16 * 4 + 12)
                val base = y * plane.rowStride + x * plane.pixelStride
                if (base + 3 >= buf.limit()) return false
                // RGBA_8888: red, green, blue have equivalent motion energy;
                // avoid reading / allocating any full-size image.
                val c0 = buf.get(base).toInt() and 0xff
                val c1 = buf.get(base + 1).toInt() and 0xff
                val c2 = buf.get(base + 2).toInt() and 0xff
                samples[n++] = (c0 + c1 + c2) / 3
            }
        }
        return detector.observe(samples, nowMs)
    }
}
