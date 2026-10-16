package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class VideoCompatibilityPolicyTest {
    @Test fun autoKeepsOrdinaryAvcSdrAndConvertsHdrHevcOrLarge() {
        val avc = VideoDescription("video/avc", 1920, 1080, false)
        assertFalse(VideoCompatibilityPolicy.shouldConvert(VideoSendMode.AUTO, avc, true))
        assertTrue(VideoCompatibilityPolicy.shouldConvert(VideoSendMode.AUTO, avc, false))
        assertTrue(VideoCompatibilityPolicy.shouldConvert(VideoSendMode.AUTO,
            VideoDescription("video/hevc", 1920, 1080, false), true))
        assertTrue(VideoCompatibilityPolicy.shouldConvert(VideoSendMode.AUTO,
            VideoDescription("video/avc", 1920, 1080, true), true))
        assertTrue(VideoCompatibilityPolicy.shouldConvert(VideoSendMode.AUTO,
            VideoDescription("video/avc", 3840, 2160, false), true))
    }
    @Test fun originalNeverConvertsAndCompatibleAlwaysDoes() {
        val hevc = VideoDescription("video/hevc", 3840, 2160, true)
        assertFalse(VideoCompatibilityPolicy.shouldConvert(VideoSendMode.ORIGINAL, hevc, false))
        assertTrue(VideoCompatibilityPolicy.shouldConvert(VideoSendMode.COMPATIBLE, hevc, true))
    }
    @Test fun defaultModeSafeAndNamesDoNotOverwriteOriginals() {
        assertEquals(VideoSendMode.AUTO, VideoSendMode.fromKey("bad"))
        assertEquals(VideoSendMode.ORIGINAL, VideoSendMode.fromKey("original"))
        assertEquals("clip_AirGrab_相容.mp4", VideoCompatibilityPolicy.compatibleName("clip.HEVC"))
    }
}
