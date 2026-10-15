package tw.airgesture.transferbeta

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureSequenceTest {
    @Test fun palmToFistTriggersSend() {
        val g = GestureSequence()
        assertEquals(GestureSequence.Result.NONE, g.accept("Open_Palm", 1000))
        assertEquals(GestureSequence.Result.NONE, g.accept("Open_Palm", 1320))
        assertEquals(GestureSequence.Result.NONE, g.accept("Closed_Fist", 1430))
        assertEquals(GestureSequence.Result.SEND, g.accept("Closed_Fist", 1750))
    }
    @Test fun fistToPalmTriggersReceive() {
        val g = GestureSequence()
        g.accept("Closed_Fist", 1000)
        g.accept("Closed_Fist", 1320)
        g.accept("Open_Palm", 1430)
        assertEquals(GestureSequence.Result.RECEIVE, g.accept("Open_Palm", 1750))
    }
    @Test fun isolatedSinglePoseDoesNotSend() {
        val g = GestureSequence()
        g.accept("Open_Palm", 1000)
        assertEquals(GestureSequence.Result.NONE, g.accept("Open_Palm", 2000))
    }
}