package tw.airgesture.transferbeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSwipeGeometryTest {
    @Test fun inAppLeftSwipeAvoidsBothSystemNavigationEdges() {
        val geometry = ControlSwipeGeometry.inAppLeftSwipe(1080)
        assertEquals(864f, geometry.startX, .01f)
        assertEquals(216f, geometry.endX, .01f)
        assertTrue(geometry.startX < 1080f * .90f)
        assertTrue(geometry.endX > 1080f * .10f)
    }

    @Test fun inAppLeftSwipeKeepsDirectionAndEnoughTravel() {
        for (width in listOf(720, 1080, 1440)) {
            val geometry = ControlSwipeGeometry.inAppLeftSwipe(width)
            assertTrue(geometry.startX > geometry.endX)
            assertTrue(geometry.startX - geometry.endX >= width * .55f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidScreenWidthIsRejected() {
        ControlSwipeGeometry.inAppLeftSwipe(0)
    }
}
