package tw.airgesture.transferbeta.subtitle;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SubtitleCandidatePolicyTest {
    @Test public void landscapeBottomDialogueOutranksToolbarText() {
        SubtitleCandidatePolicy.Candidate toolbar =
            new SubtitleCandidatePolicy.Candidate("Watch this channel",
                    20, 20, 500, 65);
        SubtitleCandidatePolicy.Candidate dialogue =
            new SubtitleCandidatePolicy.Candidate("But then I remembered that night on the train",
                    410, 780, 1980, 843);
        assertEquals(dialogue, SubtitleCandidatePolicy.best(
                Arrays.asList(toolbar, dialogue), 2400, 1080, null, 0));
    }

    @Test public void canFindSubtitleNearTopInPortraitWithoutFixedCrop() {
        SubtitleCandidatePolicy.Candidate title =
            new SubtitleCandidatePolicy.Candidate("Video title and creator details",
                    15, 1370, 590, 1410);
        SubtitleCandidatePolicy.Candidate dialogue =
            new SubtitleCandidatePolicy.Candidate("This is the text above the middle of the screen",
                    180, 760, 900, 820);
        assertEquals(dialogue, SubtitleCandidatePolicy.best(
                Arrays.asList(title, dialogue), 1080, 2400, null, 0));
    }

    @Test public void avoidStickingToUnchangedTitleWhenCaptionsMove() {
        SubtitleCandidatePolicy.Candidate staticTitle =
            new SubtitleCandidatePolicy.Candidate("Alice and her very wonderful story",
                    440, 740, 1670, 790);
        SubtitleCandidatePolicy.Candidate movingDialogue =
            new SubtitleCandidatePolicy.Candidate("I remembered what she said before we left",
                    390, 875, 1720, 925);
        SubtitleCandidatePolicy.Candidate best = SubtitleCandidatePolicy.best(
                Arrays.asList(staticTitle, movingDialogue), 2160, 1080,
                staticTitle, 8);
        assertEquals(movingDialogue, best);
    }

    @Test public void fullScreenBoundsCoverAllVerticalPositions() {
        int[] p = SubtitleRegionPolicy.verticalBounds(1080, 2400,
                SubtitleRegionPolicy.FULL_SCREEN, 20, 85);
        assertArrayEquals(new int[]{0, 2400}, p);
    }
}
