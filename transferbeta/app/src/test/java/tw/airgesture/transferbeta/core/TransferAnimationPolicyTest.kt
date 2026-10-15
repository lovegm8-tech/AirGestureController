package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class TransferAnimationPolicyTest {
    @Test fun startingShareDoesNotMeanSuccess() {
        assertFalse(TransferAnimationPolicy.isSuccess(
            VisualEvent(VisualPhase.CAPTURING, "已點擊分享")))
    }
    @Test fun capturedContentCanShowGreenSuccess() {
        assertTrue(TransferAnimationPolicy.isSuccess(
            VisualEvent(VisualPhase.CONTENT_READY, "原檔已取得")))
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
    @Test fun senderReceiverPaletteShouldDiffer() {
        assertFalse(TransferAnimationPolicy.isReceiver(
            VisualEvent(VisualPhase.CONTENT_READY, "發送端")))
        assertTrue(TransferAnimationPolicy.isReceiver(
            VisualEvent(VisualPhase.RECEIVING, "接收端")))
    }
    @Test fun unknownProgressClamps() {
        assertEquals(0,TransferAnimationPolicy.displayProgress(
            VisualEvent(VisualPhase.RECEIVING, "receiving", -40)))
        assertEquals(100,TransferAnimationPolicy.displayProgress(
            VisualEvent(VisualPhase.RECEIVING, "receiving", 400)))
    }
}
