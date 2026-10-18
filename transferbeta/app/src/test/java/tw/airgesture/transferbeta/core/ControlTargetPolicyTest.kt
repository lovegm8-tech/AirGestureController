package tw.airgesture.transferbeta.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlTargetPolicyTest {
    @Test fun youtubeTapIsConvertedToMediaKeyAtDispatch() {
        assertEquals(FloatingControlRouter.Command.MEDIA_PLAY_PAUSE,
            ControlTargetPolicy.commandForForeground(
                FloatingControlRouter.Command.TAP, "com.google.android.youtube"))
    }

    @Test fun nonYoutubeTapStaysTap() {
        assertEquals(FloatingControlRouter.Command.TAP,
            ControlTargetPolicy.commandForForeground(
                FloatingControlRouter.Command.TAP, "com.android.chrome"))
    }

    @Test fun cachedMediaCommandDoesNotTurnIntoUnwantedTouch() {
        assertEquals(FloatingControlRouter.Command.MEDIA_PLAY_PAUSE,
            ControlTargetPolicy.commandForForeground(
                FloatingControlRouter.Command.MEDIA_PLAY_PAUSE, null))
    }

    @Test fun swipesAndTransfersRemainUnchanged() {
        for (command in FloatingControlRouter.Command.values()) {
            if (command == FloatingControlRouter.Command.TAP) continue
            assertEquals(command,
                ControlTargetPolicy.commandForForeground(
                    command, "com.google.android.youtube"))
        }
    }
}
