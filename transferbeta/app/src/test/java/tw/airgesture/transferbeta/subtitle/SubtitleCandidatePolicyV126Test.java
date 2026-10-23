package tw.airgesture.transferbeta.subtitle;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleCandidatePolicyV126Test {
    @Test public void portraitPlayerSubtitleBeatsMetadataPanel() {
        SubtitleCandidatePolicy.Candidate caption = new SubtitleCandidatePolicy.Candidate(
            "The one thing that I still remember", 162, 660, 905, 711);
        SubtitleCandidatePolicy.Candidate metadata = new SubtitleCandidatePolicy.Candidate(
            "Subscribe to our new video updates and news", 80, 1260, 990, 1311);
        assertEquals(caption, SubtitleCandidatePolicy.best(Arrays.asList(metadata, caption),
            1080, 2400, null, 0));
    }

    @Test public void joinsTwoSubtitleLinesAcrossDifferentOcrBlocks() {
        SubtitleCandidatePolicy.Candidate first = new SubtitleCandidatePolicy.Candidate(
            "But then I remembered", 400, 780, 1280, 812);
        SubtitleCandidatePolicy.Candidate second = new SubtitleCandidatePolicy.Candidate(
            "that night on the train", 398, 816, 1281, 853);
        List<SubtitleCandidatePolicy.Candidate> result =
            SubtitleCandidatePolicy.joinNearbyLines(Arrays.asList(first, second), 1920);
        assertEquals(1, result.size());
        assertEquals("But then I remembered that night on the train", result.get(0).text);
        assertEquals(780, result.get(0).top);
        assertEquals(853, result.get(0).bottom);
    }

    @Test public void doesNotMergeScatteredMenuItems() {
        SubtitleCandidatePolicy.Candidate first = new SubtitleCandidatePolicy.Candidate(
            "Save video", 20, 280, 160, 315);
        SubtitleCandidatePolicy.Candidate second = new SubtitleCandidatePolicy.Candidate(
            "Video details", 650, 680, 950, 716);
        assertTrue(SubtitleCandidatePolicy.joinNearbyLines(
            Arrays.asList(first, second), 1080).isEmpty());
    }
}
