package tw.airgesture.transferbeta.subtitle;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleOcrVoiceArbitrationTest {
    private SubtitleCandidatePolicy.Candidate c(String s, int l, int t, int r, int b) {
        return new SubtitleCandidatePolicy.Candidate(s, l, t, r, b);
    }
    @Test public void needsRepeatedTextInVideoRegion() {
        var video = c("You have to suffer", 160, 570, 920, 640);
        assertFalse(SubtitleOcrVoiceArbitration.isTrusted(video, video, 0, false, 1080, 2400));
        assertTrue(SubtitleOcrVoiceArbitration.isTrusted(video, video, 1, false, 1080, 2400));
        var page = c("Recommended search", 160, 1160, 920, 1230);
        assertFalse(SubtitleOcrVoiceArbitration.isTrusted(page, page, 2, false, 1080, 2400));
        assertFalse(SubtitleOcrVoiceArbitration.isTrusted(video, page, 2, false, 1080, 2400));
    }
    @Test public void shortVideoLandscapeAndManualRoi() {
        var shorts = c("Another sentence here", 140, 1710, 940, 1770);
        assertTrue(SubtitleOcrVoiceArbitration.isTrusted(shorts, shorts, 1, false, 1080, 2400));
        var wide = c("Speech subtitle", 450, 700, 1900, 780);
        assertTrue(SubtitleOcrVoiceArbitration.isTrusted(wide, wide, 1, false, 2400, 1080));
        var manual = c("Some dialog here", 80, 1100, 500, 1150);
        assertTrue(SubtitleOcrVoiceArbitration.isTrusted(manual, manual, 1, true, 1080, 2400));
    }
    @Test public void trustExpiresAndPassiveScanIsThrottled() {
        assertFalse(SubtitleOcrVoiceArbitration.prefersOcr(2000L, 0L));
        assertTrue(SubtitleOcrVoiceArbitration.prefersOcr(3000L, 2000L));
        assertFalse(SubtitleOcrVoiceArbitration.prefersOcr(4300L, 2000L));
        assertFalse(SubtitleOcrVoiceArbitration.shouldProbe(1500L, 700L, 0L));
        assertTrue(SubtitleOcrVoiceArbitration.shouldProbe(2200L, 700L, 0L));
    }
}
