package tw.airgesture.transferbeta.subtitle;

import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleSelectionV128Test {
    private static SubtitleCandidatePolicy.Candidate line(
            String text, int x1, int y1, int x2, int y2) {
        return new SubtitleCandidatePolicy.Candidate(text, x1, y1, x2, y2);
    }

    @Test public void recordedPageChromeIsRecognizedAsEmbeddedPlayer() {
        List<SubtitleCandidatePolicy.Candidate> raw = Arrays.asList(
                line("搜尋結果內容", 160, 910, 560, 956),
                line("Harry Potter", 570, 921, 970, 968),
                line("Lofi Chill 1小時萌寵天風呂", 130, 1660, 890, 1710));
        assertTrue(SubtitleSelectionPolicy.isEmbeddedPlayerPage(raw, 1080, 2340));
    }

    @Test public void mustNotTranslateRecommendedVideoKeywordWhenCaptionMissing() {
        List<SubtitleCandidatePolicy.Candidate> candidates = Arrays.asList(
                line("Harry Potter", 250, 950, 820, 1000),
                line("Tharry Potter Efseskh", 260, 1030, 820, 1090),
                line("Lofi Chill Black Cat", 130, 1630, 920, 1680));
        assertNull(SubtitleSelectionPolicy.select(candidates, 1080, 2340,
                null, 0, true, false));
    }

    @Test public void preferFantasticNevilleActualCaptionInsidePlayer() {
        SubtitleCandidatePolicy.Candidate actual =
                line("Fantastic, Neville. Well done.", 250, 770, 845, 815);
        List<SubtitleCandidatePolicy.Candidate> candidates = Arrays.asList(
                actual,
                line("Harry Potter", 250, 950, 820, 1000),
                line("Lofi Chill Black Cat", 130, 1630, 920, 1680));
        assertSame(actual, SubtitleSelectionPolicy.select(candidates,
                1080, 2340, null, 0, true, false));
    }

    @Test public void videoOnlyPassRejectsEverythingOutsidePlayer() {
        assertNull(SubtitleSelectionPolicy.select(
                Collections.singletonList(line("Harry Potter", 200, 980, 780, 1040)),
                1080, 2340, null, 0, false, true));
    }

    @Test public void shortsLowerCaptionStillWorksWithoutPageChrome() {
        SubtitleCandidatePolicy.Candidate actual =
                line("But I do remember that summer", 160, 1700, 950, 1760);
        assertSame(actual, SubtitleSelectionPolicy.select(
                Collections.singletonList(actual), 1080, 2340,
                null, 0, false, false));
    }

    @Test public void landscapeCaptionUnchanged() {
        SubtitleCandidatePolicy.Candidate actual =
                line("Fantastic Neville well done", 400, 800, 1800, 875);
        assertSame(actual, SubtitleSelectionPolicy.select(
                Collections.singletonList(actual), 2400, 1080,
                null, 0, false, false));
    }
}
