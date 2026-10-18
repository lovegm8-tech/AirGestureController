package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class MultiReceiverBatchTest {
    private val a = "s26"
    private val b = "tab"
    private val c = "iphone"

    @Test fun eachRecipientAdvancesWithoutReSendingVerifiedFiles() {
        val flow = MultiReceiverBatch("queue", 0L, fileCount = 4)
        assertTrue(flow.announce(a, 0))
        assertTrue(flow.announce(b, 0))
        assertTrue(flow.accept(a, 100))
        assertTrue(flow.accept(b, 200))
        for (index in 0 until 4) {
            assertEquals(a, flow.claimNext())
            assertEquals(index, flow.nextIndex(a))
            assertTrue(flow.bytesFinished(a))
            assertTrue(flow.complete(a))
            assertEquals(index + 1, flow.progress(a))
            assertFalse(flow.accept(a, 500L))
            if (index < 3) assertEquals(MultiReceiverBatch.State.QUEUED,
                flow.state(a))
            if (index == 0) {
                assertEquals(a, flow.claimNext()) // queue insertion order
                assertEquals(1, flow.nextIndex(a))
                // claimNext() intentionally occupies the sender slot.
                assertTrue(flow.fail(a))
                break
            }
        }
        assertEquals(1, flow.progress(a))
        assertEquals(b, flow.claimNext())
        assertEquals(0, flow.nextIndex(b))
        assertTrue(flow.complete(b))
        assertEquals(1, flow.progress(b))
    }

    @Test fun aPeerCompletesEntireBatchWhileAnotherFailsMidway() {
        val flow = MultiReceiverBatch("queue", 0L, fileCount = 3)
        flow.announce(a, 0)
        flow.announce(b, 0)
        flow.accept(a, 100)
        flow.accept(b, 110)
        for (i in 0 until 3) {
            assertEquals(a, flow.claimNext())
            assertEquals(i, flow.nextIndex(a))
            assertTrue(flow.bytesFinished(a))
            assertTrue(flow.complete(a))
        }
        assertEquals(MultiReceiverBatch.State.COMPLETE, flow.state(a))
        assertEquals(1, flow.completedCount())
        assertEquals(3, flow.progress(a))
        assertEquals(b, flow.claimNext())
        assertEquals(0, flow.nextIndex(b))
        assertTrue(flow.complete(b))
        assertEquals(b, flow.claimNext())
        assertEquals(1, flow.nextIndex(b))
        assertTrue(flow.fail(b))
        assertEquals(1, flow.failedCount())
        assertEquals(1, flow.progress(b)) // first file stays completed
        flow.endUnclaimed(30_001)
        assertTrue(flow.isFinished(30_001))
    }

    @Test fun latePeerCannotJoinAfterThirtySecondsButExistingPeerKeepsProgress() {
        val flow = MultiReceiverBatch("queue", 0L, fileCount = 2)
        flow.announce(a, 0)
        flow.announce(b, 0)
        assertTrue(flow.accept(a, 29_999))
        flow.endUnclaimed(30_000)
        assertFalse(flow.accept(b, 30_000))
        assertEquals(a, flow.claimNext())
        assertTrue(flow.complete(a))
        assertEquals(1, flow.progress(a))
        assertEquals(a, flow.claimNext())
        assertTrue(flow.complete(a))
        assertEquals(2, flow.progress(a))
        assertTrue(flow.isFinished(30_000))
    }

    @Test fun severalReceiversCanJoinIndependentlyBeforeThirtySeconds() {
        val flow = MultiReceiverBatch("session", 10_000L)
        assertTrue(flow.announce(a, 10_000))
        assertTrue(flow.announce(b, 10_005))
        assertTrue(flow.announce(c, 32_000))
        flow.seen(a)
        assertTrue(flow.accept(a, 12_000))
        assertTrue(flow.accept(b, 29_000))
        assertTrue(flow.accept(c, 39_999))
        assertEquals(a, flow.claimNext())
        assertNull(flow.claimNext()) // one large-file transfer at a time
        assertTrue(flow.bytesFinished(a))
        assertTrue(flow.complete(a))
        assertEquals(b, flow.claimNext())
        assertTrue(flow.fail(b))
        assertEquals(c, flow.claimNext())
        assertTrue(flow.bytesFinished(c))
        assertTrue(flow.complete(c))
        assertEquals(2, flow.completedCount())
        assertEquals(1, flow.failedCount())
        assertFalse(flow.isFinished(39_999))
        assertTrue(flow.isFinished(40_000))
    }
    @Test fun lateReceiversCannotJoinAndAcceptedOnesContinueAfterWindow() {
        val flow = MultiReceiverBatch("session", 1_000)
        assertTrue(flow.announce(a, 1_000))
        assertTrue(flow.accept(a, 30_999))
        assertFalse(flow.announce(b, 31_000))
        assertFalse(flow.accept(a, 31_000))
        flow.endUnclaimed(31_000)
        assertEquals(a, flow.claimNext())
        assertTrue(flow.complete(a))
        assertTrue(flow.isFinished(31_000))
    }
    @Test fun disconnectedReceiverDoesNotCancelQueue() {
        val flow = MultiReceiverBatch("session", 0)
        flow.announce(a, 0)
        flow.announce(b, 0)
        flow.accept(a, 10)
        flow.accept(b, 11)
        assertEquals(a, flow.claimNext())
        assertTrue(flow.fail(a))
        assertEquals(b, flow.claimNext())
        assertTrue(flow.complete(b))
        assertFalse(flow.fail(b))
    }
    @Test fun repeatedRequestsDoNotDuplicateTransfers() {
        val flow = MultiReceiverBatch("session", 1)
        assertTrue(flow.announce(a, 2))
        assertFalse(flow.announce(a, 3))
        assertTrue(flow.accept(a, 4))
        assertFalse(flow.accept(a, 5))
        assertEquals(a, flow.claimNext())
        assertFalse(flow.accept(a, 6))
    }
    @Test fun onlyAlreadyAcknowledgedSendsContinueAfterExpiry() {
        val flow = MultiReceiverBatch("session", 0)
        flow.announce(a, 0)
        flow.announce(b, 0)
        assertTrue(flow.accept(a, 100))
        flow.endUnclaimed(30_001)
        assertEquals(MultiReceiverBatch.State.FAILED, flow.state(b))
        assertEquals(a, flow.claimNext())
    }
}
