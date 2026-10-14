package tw.airgesture.controller.core

/**
 * Maps the small area reachable by an extended index finger in the front camera
 * to the whole display. Only affects the finger cursor, never the palm swipe engine.
 */
class CursorCoordinateMapper(private val preset: Int = 2) {
    data class Position(val x: Float, val y: Float)

    val gain: Float = when (preset) {
        0 -> 1f
        1 -> 2f
        2 -> 3f
        3 -> 4f
        else -> 3f
    }

    fun map(x: Float, y: Float): Position? {
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
        return Position(
            ((x - .5f) * gain + .5f).coerceIn(.008f, .992f),
            ((y - .5f) * gain + .5f).coerceIn(.008f, .992f)
        )
    }
}
