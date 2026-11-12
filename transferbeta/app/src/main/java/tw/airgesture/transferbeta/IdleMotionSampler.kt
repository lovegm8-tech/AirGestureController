package tw.airgesture.transferbeta

import androidx.camera.core.ImageProxy
import tw.airgesture.transferbeta.core.IdleMotionDetector

/**
 * Reads 192 small RGBA samples every 180ms in screen-on ECO, without converting
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
        // Sample to 94% of image height instead of stopping at 83%.
        // A raised palm often enters from the bottom of the front camera.
        // These are inexpensive samples; the actual ML pose still must be
        // identified before an action is accepted.
        for (row in 0 until 12) {
            val y = (h * (.02f + (row + .5f) * .96f / 12f))
                .toInt().coerceIn(0, h - 1)
            for (col in 0 until 16) {
                val x = (w * (.05f + (col + .5f) * .90f / 16f))
                    .toInt().coerceIn(0, w - 1)
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
