package tw.airgesture.transferbeta.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleVoiceFallbackTest {
    @Test public void correctLicensedEnglishAndJapaneseOfflineModelsSelected() {
        assertTrue(SubtitleSpeechModels.supports("en"));
        assertTrue(SubtitleSpeechModels.supports("ja"));
        assertFalse(SubtitleSpeechModels.supports("ko"));
        assertEquals("vosk-model-small-en-us-0.15", SubtitleSpeechModels.folder("en"));
        assertEquals("vosk-model-small-ja-0.22", SubtitleSpeechModels.folder("ja"));
        assertEquals("https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
            SubtitleSpeechModels.downloadUrl("ja"));
    }

    @Test public void JapaneseShortAnimeUtterancesAllowed() {
        assertEquals(3, SubtitleSpeechModels.minFinalLength("ja"));
        assertEquals(7, SubtitleSpeechModels.minPartialLength("ja"));
        assertTrue(SubtitleSpeechModels.minFinalLength("ja") <
                SubtitleSpeechModels.minFinalLength("en"));
    }

    @Test(expected=IllegalArgumentException.class)
    public void unsupportedSpeechSourceNotSilentlyMislabeled() {
        SubtitleSpeechModels.folder("ko");
    }


    @Test public void voiceCanReplaceRecentOcr() {
        assertTrue(SubtitleVoicePriority.mayDisplayVoice(true,true,false,false,
                10_000,9_900,0));
        assertTrue(SubtitleVoicePriority.mayDisplayVoice(true,true,false,false,
                10_000,9_900,8_200));
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,true,false,false,
                10_000,0,9_000));
    }
    @Test public void ocrWaitsForSpeechWarmupAndThenFillsGaps() {
        assertFalse(SubtitleVoicePriority.mayScanOcr(true,false,false,false,
                5_000,1_000,0));
        assertTrue(SubtitleVoicePriority.mayScanOcr(true,false,false,false,
                8_000,1_000,0));
        assertFalse(SubtitleVoicePriority.mayScanOcr(true,false,false,false,
                10_000,1_000,7_500));
        assertTrue(SubtitleVoicePriority.mayScanOcr(true,false,false,false,
                13_000,1_000,7_500));
        assertTrue(SubtitleVoicePriority.mayScanOcr(true,true,false,false,
                2_000,1_000,0));
    }
    @Test public void freshVoiceCaptionBeatsOcr() {
        assertFalse(SubtitleVoicePriority.mayDisplayOcr(true,10_000,9_900));
        assertTrue(SubtitleVoicePriority.mayDisplayOcr(true,16_000,9_900));
        assertTrue(SubtitleVoicePriority.mayDisplayOcr(false,10_000,9_900));
    }
    @Test public void respectPausedOrEditingState() {
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,true,true,false,
                10_000,0,0));
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,true,false,true,
                10_000,0,0));
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(false,true,false,false,
                10_000,0,0));
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,false,false,false,
                10_000,0,0));
        assertFalse(SubtitleVoicePriority.mayScanOcr(true,false,true,false,
                10_000,0,0));
        assertFalse(SubtitleVoicePriority.mayScanOcr(false,false,false,true,
                10_000,0,0));
    }
}
