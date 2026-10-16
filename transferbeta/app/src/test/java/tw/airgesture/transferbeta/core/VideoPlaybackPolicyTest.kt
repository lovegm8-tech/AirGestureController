package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackPolicyTest {
    @Test fun codecDiagnosticDistinguishesAvcFromHevc() {
        assertEquals("H.264/AVC", VideoPlaybackPolicy.codecName("video/avc"))
        assertEquals("H.265/HEVC", VideoPlaybackPolicy.codecName("video/hevc"))
    }
    @Test fun noVideoTrackIsNotACompletePlayableVideo() {
        assertTrue(VideoPlaybackPolicy.verifiedMessage(null, null).contains("沒有可辨識"))
    }
    @Test fun unsupportedDecoderDoesNotPretendTransferLostBytes() {
        val message = VideoPlaybackPolicy.verifiedMessage("video/hevc", false)
        assertTrue(message.contains("原檔完整"))
        assertTrue(message.contains("沒有") || message.contains("未找到"))
        assertTrue(message.contains("H.264"))
    }
    @Test fun codecDetectionDoesNotPromiseWorkingPlayback() {
        val message = VideoPlaybackPolicy.verifiedMessage("video/avc", true)
        assertTrue(message.contains("若有聲無影"))
        assertTrue(message.contains("原檔完整"))
    }
}
