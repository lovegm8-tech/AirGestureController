package tw.airgesture.transferbeta.subtitle;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitlePolicyV127Test {
    @Test public void englishOcrRejectsChineseRecommendedVideos() {
        assertFalse(SubtitleRegionPolicy.isPossibleSubtitle(
            "1小時露天風呂 Lofi Chill 黑貓鴨鴨陪伴溫泉療癒聆聽", "en"));
        assertFalse(SubtitleRegionPolicy.isPossibleSubtitle(
            "Manky舊靴子，伴侶。", "en"));
        assertTrue(SubtitleRegionPolicy.isPossibleSubtitle(
            "[George] It's a Portkey.", "en"));
        assertTrue(SubtitleRegionPolicy.isPossibleSubtitle(
            "[Arthur] We don't want to be late.", "en"));
    }

    @Test public void portraitPlayerCaptionBeatsEnglishPageTitleBelowPlayer() {
        SubtitleCandidatePolicy.Candidate dialogue =
            new SubtitleCandidatePolicy.Candidate(
                "[Arthur] We don't want to be late.", 164, 727, 944, 787);
        SubtitleCandidatePolicy.Candidate recommendation =
            new SubtitleCandidatePolicy.Candidate(
                "Watch Harry Potter and the Goblet of Fire now", 80, 965, 1000, 1010);
        assertEquals(dialogue, SubtitleCandidatePolicy.best(
            Arrays.asList(recommendation, dialogue), 1080, 2340, null, 0));
    }

    @Test public void shortsLowerDialogCanStillWinWithoutUpperPlayerSubtitle() {
        SubtitleCandidatePolicy.Candidate dialogue =
            new SubtitleCandidatePolicy.Candidate(
                "Today we're exploring the haunted house together",
                160, 1750, 950, 1810);
        SubtitleCandidatePolicy.Candidate button =
            new SubtitleCandidatePolicy.Candidate(
                "Subscribe", 800, 1000, 1020, 1050);
        assertEquals(dialogue, SubtitleCandidatePolicy.best(
            Arrays.asList(button, dialogue), 1080, 2340, null, 0));
    }

    @Test public void japaneseAndKoreanRemainSelectable() {
        assertTrue(SubtitleRegionPolicy.isPossibleSubtitle("こんにちは", "ja"));
        assertTrue(SubtitleRegionPolicy.isPossibleSubtitle("안녕하세요", "ko"));
    }
}
