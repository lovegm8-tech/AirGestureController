package tw.airgesture.controller.core

import org.junit.Assert.*
import org.junit.Test

class WakePowerScheduleTest {
    @Test fun cameraStaysOnBrieflyWhenHandHasRecentlyAppeared() {
        assertTrue(WakePowerSchedule.shouldExtendScan(3100, 0, 3000))
        assertTrue(WakePowerSchedule.shouldExtendScan(4300, 0, 4200))
        assertFalse(WakePowerSchedule.shouldExtendScan(5300, 0, 5250))
    }

    @Test fun staleOrFutureHandDoesNotExtendCameraWindow() {
        assertFalse(WakePowerSchedule.shouldExtendScan(3500, 0, 2500))
        assertFalse(WakePowerSchedule.shouldExtendScan(3500, 0, -1))
        assertFalse(WakePowerSchedule.shouldExtendScan(3500, 0, 4000))
    }

    @Test fun automaticTenMinuteStopLimitsCameraAndCpuActivity() {
        assertFalse(WakePowerSchedule.expired(599_999, 0))
        assertTrue(WakePowerSchedule.expired(600_000, 0))
        assertTrue(WakePowerSchedule.expired(611_000, 0))
        assertFalse(WakePowerSchedule.expired(800_000, 810_000))
    }

    @Test fun intermittentModeHasActualCameraOffWindow() {
        assertTrue(WakePowerSchedule.SCAN_WINDOW_MS < WakePowerSchedule.REST_WINDOW_MS)
        assertTrue(WakePowerSchedule.REST_WINDOW_MS >= 1000L)
    }
}
