package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class OfferDeliveryTrackerTest {
    @Test fun locallyPreparedIsNotDelivered() {
        val t = OfferDeliveryTracker("send-12345", 1000L)
        assertFalse(t.wasAcknowledged())
        assertTrue(t.recordAttempt(1000L))
        assertFalse(t.wasAcknowledged())
        assertTrue(t.shouldRetry(2250L))
    }
    @Test fun unrelatedAckCannotUnlockSend() {
        val t = OfferDeliveryTracker("offer-A", 0)
        t.recordAttempt(0)
        assertFalse(t.acknowledge("offer-B"))
        assertTrue(t.shouldRetry(9000))
    }
    @Test fun matchingAckStopsRetransmission() {
        val t = OfferDeliveryTracker("offer-A", 100L)
        assertTrue(t.recordAttempt(100))
        assertTrue(t.acknowledge("offer-A"))
        assertTrue(t.wasAcknowledged())
        assertFalse(t.shouldRetry(5000))
        assertFalse(t.recordAttempt(5000))
    }
    @Test fun timeoutNoLongerRetries() {
        val t = OfferDeliveryTracker("offer-A", 1000)
        assertTrue(t.recordAttempt(1000))
        assertTrue(t.shouldRetry(30999))
        assertFalse(t.shouldRetry(31000))
        assertTrue(t.hasExpired(31000))
        assertFalse(t.recordAttempt(32000))
    }
}
