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

    @Test public void freshOcrAlwaysBlocksVoiceReplacement() {
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,true,false,false,
                10_000,9_900,0));
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,true,false,false,
                10_000,8_000,0));
    }

    @Test public void staleOcrAllowsSpeechForAnimeAndEnglish() {
        assertTrue(SubtitleVoicePriority.mayDisplayVoice(true,true,false,false,
                10_000,7_000,0));
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,true,false,false,
                10_000,7_000,9_000));
        assertTrue(SubtitleVoicePriority.mayDisplayVoice(true,true,false,false,
                10_000,7_000,7_000));
    }

    @Test public void neverTranslateWhenPausedEditingOrOptedOut() {
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(false,true,false,false,
                10_000,0,0));
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,true,true,false,
                10_000,0,0));
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,true,false,true,
                10_000,0,0));
        assertFalse(SubtitleVoicePriority.mayDisplayVoice(true,false,false,false,
                10_000,0,0));
    }
}
