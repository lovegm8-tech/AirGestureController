package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class TransferSpeedTrackerTest {
    @Test fun speedAndEtaAreComputedOnlyAfterOneSecond() {
        val tracker = TransferSpeedTracker()
        val mib = 1024L * 1024L
        assertNull(tracker.observe(0, 8 * mib, 1000))
        assertNull(tracker.observe(mib / 2, 8 * mib, 1400))
        val snapshot = tracker.observe(2 * mib, 8 * mib, 2000)
        assertNotNull(snapshot)
        assertEquals(2.0, snapshot!!.mebibytesPerSecond, .001)
        assertEquals(3L, snapshot.remainingSeconds)
        assertTrue(TransferSpeedTracker.summary(snapshot).contains("2.0 MiB/s"))
    }

    @Test fun resetOnDiscontinuousProgressOrClock() {
        val tracker = TransferSpeedTracker()
        tracker.observe(0, 10_000_000L, 1000)
        tracker.observe(2_000_000, 10_000_000, 2000)
        assertNull(tracker.observe(0, 10_000_000, 3000))
        assertNull(tracker.observe(500_000, 10_000_000, 3100))
        assertNull(tracker.observe(1_000_000, 10_000_000, 1000))
    }

    @Test fun invalidProgressNeverInflatesRate() {
        val tracker = TransferSpeedTracker()
        assertNull(tracker.observe(100, 0, 1000))
        assertNull(tracker.observe(200, 100, 1000))
        assertNull(tracker.observe(-1, 100, 1000))
    }
}
