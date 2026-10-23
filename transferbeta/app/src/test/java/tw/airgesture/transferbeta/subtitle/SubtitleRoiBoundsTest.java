package tw.airgesture.transferbeta.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleRoiBoundsTest {
    @Test public void portraitBoxIsLimitedToChosenSubtitleStrip() {
        int[] p = SubtitleRoiBounds.pixels(0.14f,0.28f,0.86f,0.36f,1080,2400);
        assertArrayEquals(new int[]{151,672,929,864},p);
        assertTrue("recommended videos below player excluded", p[3]<900);
    }

    @Test public void landscapeSelectionScalesIndependently() {
        int[] p = SubtitleRoiBounds.pixels(0.12f,0.76f,0.88f,0.92f,2400,1080);
        assertArrayEquals(new int[]{288,821,2112,994},p);
    }

    @Test public void roiAlwaysStaysInsideScreenWithAtLeastOnePixel() {
        int[] p = SubtitleRoiBounds.pixels(-0.2f,-0.4f,4f,6f,1080,2400);
        assertArrayEquals(new int[]{0,0,1080,2400},p);
        int[] small = SubtitleRoiBounds.pixels(1f,1f,1f,1f,1080,2400);
        assertTrue(small[2]>small[0]);assertTrue(small[3]>small[1]);
    }

    @Test public void enlargedOcrCoordinatesMapBackToOriginalVideo() {
        assertEquals(456,SubtitleRoiBounds.toScreen(216,480f,2f));
        assertEquals(780,SubtitleRoiBounds.toScreen(600,378f,2.1f));
    }

    @Test(expected=IllegalArgumentException.class)
    public void invalidScreenIsRejected() {
        SubtitleRoiBounds.pixels(0,0,1,1,0,1080);
    }

    @Test(expected=IllegalArgumentException.class)
    public void invalidScaleIsRejected() {
        SubtitleRoiBounds.toScreen(0,200f,0f);
    }
}
