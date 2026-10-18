package tw.airgesture.transferbeta.core

/**
 * Coordinates for synthetic in-app horizontal swipes.
 *
 * Samsung's system back gesture (and Good Lock edge handles) can intercept
 * strokes starting right at the screen edge. Keep app-content swipes well
 * inside both edges; SWIPE_BACK intentionally stays edge-based and separate.
 */
object ControlSwipeGeometry {
    data class Horizontal(val startX: Float, val endX: Float)

    fun inAppLeftSwipe(screenWidth: Int): Horizontal {
        require(screenWidth > 0) { "Screen width must be positive" }
        return Horizontal(startX = screenWidth * .80f, endX = screenWidth * .20f)
    }
}
