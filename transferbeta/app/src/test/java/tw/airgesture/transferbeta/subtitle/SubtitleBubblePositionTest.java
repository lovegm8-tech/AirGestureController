package tw.airgesture.transferbeta.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleBubblePositionTest {
    @Test public void draggedBallCannotLeaveLeftOrRightEdge() {
        assertEquals(0, SubtitleBubblePosition.clamp(-300, 900));
        assertEquals(900, SubtitleBubblePosition.clamp(1200, 900));
        assertEquals(460, SubtitleBubblePosition.clamp(460, 900));
    }
    @Test public void clampWorksWithSmallLandscapeDisplay() {
        assertEquals(0, SubtitleBubblePosition.clamp(10, -20));
        assertEquals(0, SubtitleBubblePosition.clamp(10, 0));
        assertEquals(12, SubtitleBubblePosition.clamp(55, 12));
    }
    @Test public void menuCanFlipToOtherSideOfBall() {
        int width = 1080, ballX = 984, ballWidth = 50;
        int menuWidth = 190, gap = 7;
        int wanted = ballX - menuWidth - gap;
        assertTrue(SubtitleBubblePosition.clamp(wanted, width - menuWidth) < ballX);
        ballX = 0;
        wanted = ballX - menuWidth - gap;
        if (wanted < 0) wanted = ballX + ballWidth + gap;
        assertEquals(57, SubtitleBubblePosition.clamp(wanted, width - menuWidth));
    }
}
