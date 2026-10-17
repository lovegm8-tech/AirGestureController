package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class GestureActionCuePolicyTest {
    @Test fun eachDirectionalCommandHasItsOwnAcknowledgement() {
        val up = GestureActionCuePolicy.forControl(FloatingControlRouter.Command.SWIPE_UP)
        val down = GestureActionCuePolicy.forControl(FloatingControlRouter.Command.SWIPE_DOWN)
        val back = GestureActionCuePolicy.forControl(FloatingControlRouter.Command.SWIPE_BACK)
        assertEquals("↑", up?.glyph)
        assertEquals("向上滑", up?.label)
        assertEquals("↓", down?.glyph)
        assertEquals("向下滑", down?.label)
        assertEquals("→", back?.glyph)
        assertEquals("返回上一頁", back?.label)
        assertNotEquals(up, down)
    }

    @Test fun tapsAndYoutubeMediaHaveDistinctCues() {
        assertEquals("點擊",
            GestureActionCuePolicy.forControl(FloatingControlRouter.Command.TAP)?.label)
        assertEquals("播放／暫停",
            GestureActionCuePolicy.forControl(FloatingControlRouter.Command.MEDIA_PLAY_PAUSE)?.label)
        assertEquals("食指點擊", GestureActionCuePolicy.forPointerDwell().label)
    }

    @Test fun transferNeverGetsMisleadingControlSuccessCue() {
        assertNull(GestureActionCuePolicy.forControl(FloatingControlRouter.Command.SEND))
        assertNull(GestureActionCuePolicy.forControl(FloatingControlRouter.Command.RECEIVE))
    }
}
