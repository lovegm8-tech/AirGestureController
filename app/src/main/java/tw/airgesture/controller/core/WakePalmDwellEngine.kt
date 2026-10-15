package tw.airgesture.controller.core

/** Independent screen-off hold recognizer; never generates touches or bypasses authentication. */
class WakePalmDwellEngine(
    private val holdMs: Long = 850L,
    private val maxGapMs: Long = 450L,
    private val requiredSamples: Int = 3
) {
    private var firstMs = -1L
    private var lastMs = -1L
    private var samples = 0
    private var fired = false

    fun reset() {
        firstMs = -1L
        lastMs = -1L
        samples = 0
        fired = false
    }

    /** True exactly once per continuous, confirmed palm hold. */
    fun accept(isPalm: Boolean, nowMs: Long): Boolean {
        if (fired) return false
        if (!isPalm) {
            firstMs = -1L
            lastMs = -1L
            samples = 0
            return false
        }
        if (firstMs < 0L || lastMs < 0L || nowMs < lastMs || nowMs - lastMs > maxGapMs) {
            firstMs = nowMs
            samples = 0
        }
        lastMs = nowMs
        samples++
        if (samples >= requiredSamples && nowMs - firstMs >= holdMs) {
            fired = true
            return true
        }
        return false
    }
}
