package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class CursorCoordinateMapperTest {
    @Test fun centerIsStableRegardlessOfMagnification() {
        for (mode in 0..3) {
            val position = CursorCoordinateMapper(mode).map(.5f, .5f)!!
            assertEquals(.5f, position.x, .00001f)
            assertEquals(.5f, position.y, .00001f)
        }
    }

    @Test fun newDefaultCanReachBothScreenEdgesWithModerateFingerTravel() {
        val mapper = CursorCoordinateMapper()
        val topLeft = mapper.map(.30f, .30f)!!
        val bottomRight = mapper.map(.70f, .70f)!!
        assertTrue(topLeft.x < .02f)
        assertTrue(topLeft.y < .02f)
        assertTrue(bottomRight.x > .98f)
        assertTrue(bottomRight.y > .98f)
    }

    @Test fun standardModeRetainsOldOneToOneCoordinatesWithoutEdgeExclusion() {
        val mapper = CursorCoordinateMapper(0)
        val position = mapper.map(.25f, .85f)!!
        assertEquals(.25f, position.x, .0001f)
        assertEquals(.85f, position.y, .0001f)
        assertTrue(mapper.map(0f, 0f)!!.x < .01f)
    }

    @Test fun highRangeExpandsSmallFingerMotionForBothAxes() {
        val mapper = CursorCoordinateMapper(3)
        assertEquals(.10f, mapper.map(.4f, .4f)!!.x, .0001f)
        assertEquals(.90f, mapper.map(.6f, .6f)!!.y, .0001f)
    }

    @Test fun mappedCoordinatesNeverLeaveRealDisplayRange() {
        for (mode in 0..3) {
            val mapper = CursorCoordinateMapper(mode)
            for (raw in listOf(0f, .1f, .3f, .5f, .7f, .9f, 1f)) {
                val position = mapper.map(raw, raw)!!
                assertTrue(position.x >= 0f && position.x <= 1f)
                assertTrue(position.y >= 0f && position.y <= 1f)
            }
        }
    }

    @Test fun invalidHandLandmarksNeverCauseClickOrCursor() {
        val mapper = CursorCoordinateMapper()
        assertNull(mapper.map(Float.NaN, .5f))
        assertNull(mapper.map(.5f, Float.POSITIVE_INFINITY))
        assertNull(mapper.map(-.01f, .5f))
        assertNull(mapper.map(.5f, 1.01f))
    }
}
