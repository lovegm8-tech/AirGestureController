package tw.airgesture.transferbeta.core

import org.junit.Assert.*
import org.junit.Test

class GlobalGrabCoreTest {
    @Test fun ecoBoostThenSleep() {
        val p = AdaptivePowerPolicy()
        assertEquals(AdaptivePowerPolicy.Mode.STOPPED, p.mode(0))
        p.start()
        assertTrue(p.shouldInfer(1000))
        assertFalse(p.shouldInfer(1600))
        assertTrue(p.shouldInfer(2000))
        p.observeHand(true, 2000)
        assertEquals(AdaptivePowerPolicy.Mode.ACTIVE, p.mode(2100))
        assertFalse(p.shouldInfer(2110))
        assertTrue(p.shouldInfer(2120))
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(6501))
        p.screenChanged(false)
        assertEquals(AdaptivePowerPolicy.Mode.STOPPED, p.mode(7000))
        assertFalse(p.shouldInfer(7000))
        p.screenChanged(true)
        assertEquals(AdaptivePowerPolicy.Mode.ECO, p.mode(8000))
    }
    @Test fun trustedBrowserUrlOnly() {
        val id = "com.android.chrome:id/url_bar"
        val good = GrabContentPolicy.decide("com.android.chrome",
            listOf(VisibleText(id, "https://youtube.com/watch?v=123ABC")))
        assertEquals(GrabChoice.Link("https://youtube.com/watch?v=123ABC"), good)
        assertTrue(GrabContentPolicy.decide("com.google.android.youtube",
            listOf(VisibleText(id, "https://youtube.com/watch?v=123ABC")))
            is GrabChoice.Unavailable)
        assertTrue(GrabContentPolicy.decide("com.android.chrome",
            listOf(VisibleText(id, "javascript:alert(1)"))) is GrabChoice.ScreenSnapshot)
        assertTrue(GrabContentPolicy.decide("com.android.chrome",
            listOf(VisibleText(id, "https://example.com", password = true)))
            is GrabChoice.ScreenSnapshot)
    }
    @Test fun nativeYoutubeCannotBeGuessed() {
        assertTrue(GrabContentPolicy.decide("com.google.android.youtube",
            listOf(VisibleText("id/title", "An example video"))) is GrabChoice.Unavailable)
    }
    @Test fun rejectUnsafeUris() {
        assertNotNull(GrabContentPolicy.safeWebUrl("https://example.com/a"))
        assertNull(GrabContentPolicy.safeWebUrl("file:///secret.jpg"))
        assertNull(GrabContentPolicy.safeWebUrl("https://example.com/a b"))
        assertNull(GrabContentPolicy.safeWebUrl("https://name:pass@example.com"))
        assertNull(GrabContentPolicy.safeWebUrl("javascript:alert(1)"))
    }
}
