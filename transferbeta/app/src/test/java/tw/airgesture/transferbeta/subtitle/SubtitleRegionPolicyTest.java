package tw.airgesture.transferbeta.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleRegionPolicyTest {
    @Test public void automaticPortraitScansVideoAboveComments() {
        int[] r = SubtitleRegionPolicy.verticalBounds(1080, 2340,
                SubtitleRegionPolicy.AUTO, 20, 85);
        assertTrue(r[0] >= 200 && r[0] < 400);
        assertTrue(r[1] >= 1050 && r[1] <= 1250);
    }

    @Test public void automaticLandscapeTargetsSubtitlesNearBottom() {
        int[] r = SubtitleRegionPolicy.verticalBounds(2340, 1080,
                SubtitleRegionPolicy.AUTO, 20, 85);
        assertTrue(r[0] > 500);
        assertTrue(r[1] >= 1000);
    }

    @Test public void shortVideoUsesPortraitCenterLowerArea() {
        int[] r = SubtitleRegionPolicy.verticalBounds(1080, 2340,
                SubtitleRegionPolicy.SHORT_VIDEO, 20, 85);
        assertTrue(r[0] > 700);
        assertTrue(r[1] > 2000);
    }

    @Test public void customRangeClampsBadConfiguration() {
        int[] r = SubtitleRegionPolicy.verticalBounds(1000, 1800,
                SubtitleRegionPolicy.CUSTOM, 95, 20);
        assertTrue(r[0] >= 0);
        assertTrue(r[1] > r[0]);
        assertTrue(r[1] <= 1800);
    }

    @Test public void rejectsChineseOverlayWhenLanguageIsEnglish() {
        assertFalse(SubtitleRegionPolicy.isPossibleSubtitle("86令人驚嘆的娛樂", "en"));
        assertTrue(SubtitleRegionPolicy.isPossibleSubtitle("Fortuna Major", "en"));
        assertTrue(SubtitleRegionPolicy.isPossibleSubtitle("[The Fat Lady singing]", "en"));
        assertFalse(SubtitleRegionPolicy.isPossibleSubtitle("12:34", "en"));
    }

    @Test public void acceptsJapaneseAndKoreanScripts() {
        assertTrue(SubtitleRegionPolicy.isPossibleSubtitle("こんにちは", "ja"));
        assertTrue(SubtitleRegionPolicy.isPossibleSubtitle("안녕하세요", "ko"));
        assertFalse(SubtitleRegionPolicy.isPossibleSubtitle("英語字幕", "ko"));
    }
}
