package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class TransferAnimationPolicyTest {
    @Test fun startingShareDoesNotMeanSuccess() {
        assertFalse(TransferAnimationPolicy.isSuccess(
            VisualEvent(VisualPhase.CAPTURING, "已點擊分享")))
    }
    @Test fun capturedContentCanShowGreenSuccess() {
        assertFalse(TransferAnimationPolicy.isSuccess(
            VisualEvent(VisualPhase.CONTENT_READY, "原檔已取得，尚未送達")))
    }
    @Test fun progressNeverMeansVerified() {
        val event=VisualEvent(VisualPhase.RECEIVING, "正在接收", 100)
        assertFalse(TransferAnimationPolicy.isSuccess(event))
        assertEquals(100,TransferAnimationPolicy.displayProgress(event))
    }
    @Test fun receiverSuccessOnlyAfterVerifiedEvent() {
        assertTrue(TransferAnimationPolicy.isSuccess(
            VisualEvent(VisualPhase.RECEIVE_COMPLETE, "SHA 驗證完成")))
        assertFalse(TransferAnimationPolicy.isSuccess(
            VisualEvent(VisualPhase.VERIFYING, "檢查中")))
    }
    @Test fun onlyVerifiedCompletedTransferIsSuccessful() {
        assertFalse(TransferAnimationPolicy.isSuccess(
            VisualEvent(VisualPhase.WAITING_FOR_RECEIVER, "尚未送達")))
        assertTrue(TransferAnimationPolicy.isSuccess(
            VisualEvent(VisualPhase.SEND_COMPLETE, "對端已確認")))
    }
    @Test fun receiveOfferStaysVisibleForThirtySeconds() {
        for (phase in listOf(VisualPhase.WAITING_FOR_RECEIVER, VisualPhase.OFFER_RECEIVED)) {
            assertTrue(TransferAnimationPolicy.hasReceiveCountdown(phase))
            assertEquals(30_000L,
                TransferAnimationPolicy.durationMs(VisualEvent(phase, "waiting")))
        }
    }
    @Test fun onlyRealTransferEndsShowSuccessQuickly() {
        assertFalse(TransferAnimationPolicy.hasReceiveCountdown(VisualPhase.RECEIVE_COMPLETE))
        assertEquals(1900L, TransferAnimationPolicy.durationMs(
            VisualEvent(VisualPhase.RECEIVE_COMPLETE, "success")))
    }
    @Test fun senderReceiverPaletteShouldDiffer() {
        assertFalse(TransferAnimationPolicy.isReceiver(
            VisualEvent(VisualPhase.CONTENT_READY, "發送端")))
        assertTrue(TransferAnimationPolicy.isReceiver(
            VisualEvent(VisualPhase.RECEIVING, "接收端")))
    }
    @Test fun bothSidesWaitUpToThirtySeconds() {
        for (phase in listOf(VisualPhase.WAITING_FOR_RECEIVER,VisualPhase.OFFER_RECEIVED)) {
            val event=VisualEvent(phase,"等待接收")
            assertTrue(TransferAnimationPolicy.isWaiting(event))
            assertEquals(30_000L,TransferAnimationPolicy.durationMs(event))
            assertFalse(TransferAnimationPolicy.isSuccess(event))
        }
    }
    @Test fun completedOrFailedStagesDoNotRemainPinned() {
        for (phase in listOf(VisualPhase.RECEIVE_COMPLETE,VisualPhase.SEND_COMPLETE,VisualPhase.FAILURE)) {
            val event=VisualEvent(phase,"完成")
            assertFalse(TransferAnimationPolicy.isWaiting(event))
            assertTrue(TransferAnimationPolicy.durationMs(event) < 30_000L)
        }
    }
    @Test fun unknownProgressClamps() {
        assertEquals(0,TransferAnimationPolicy.displayProgress(
            VisualEvent(VisualPhase.RECEIVING, "receiving", -40)))
        assertEquals(100,TransferAnimationPolicy.displayProgress(
            VisualEvent(VisualPhase.RECEIVING, "receiving", 400)))
    }
}
