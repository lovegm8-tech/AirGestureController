package tw.airgesture.transferbeta.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleSpeechEmissionPolicyTest {
    @Test public void pollIsFastEnoughForSubSecondIntermediateCaptions() {
        assertTrue(SubtitleSpeechEmissionPolicy.PARTIAL_POLL_MS <= 600);
        assertTrue(SubtitleSpeechEmissionPolicy.PARTIAL_POLL_MS >= 300);
    }

    @Test public void matchingEnglishHypothesesCanEmitCompletePhrase() {
        assertEquals("i know it's good",
                SubtitleSpeechEmissionPolicy.stablePartial(
                        "i know it's good", "i know it's good", "en"));
        assertEquals("hello world",
                SubtitleSpeechEmissionPolicy.stablePartial(
                        "hello world", "hello world", "en"));
    }

    @Test public void changingEnglishWordIsNotEmittedUntilStable() {
        assertEquals("i know",
                SubtitleSpeechEmissionPolicy.stablePartial(
                        "i know it", "i know it's good", "en"));
        assertEquals("we will go",
                SubtitleSpeechEmissionPolicy.stablePartial(
                        "we will go to", "we will go today", "en"));
    }

    @Test public void JapanesePartialTextDoesNotRequireSpaces() {
        assertEquals("ありがとう",
                SubtitleSpeechEmissionPolicy.stablePartial(
                        "ありがとうございます", "ありがとう", "ja"));
        assertEquals("こんにちは",
                SubtitleSpeechEmissionPolicy.stablePartial(
                        "こんにちは", "こんにちは", "ja"));
    }

    @Test public void noPreviousPartialMeansNoPrematureGuess() {
        assertEquals("", SubtitleSpeechEmissionPolicy.stablePartial(
                "", "i know it's good", "en"));
        assertEquals("", SubtitleSpeechEmissionPolicy.stablePartial(
                "", "こんにちは", "ja"));
    }

    @Test public void repeatedAndTruncatedPartialsDoNotFlicker() {
        assertFalse(SubtitleSpeechEmissionPolicy.meaningfulChange(
                "i know it's good", "i know it's good", "en"));
        assertFalse(SubtitleSpeechEmissionPolicy.meaningfulChange(
                "i know it's good", "i know", "en"));
        assertFalse(SubtitleSpeechEmissionPolicy.meaningfulChange(
                "こんにちは", "こんに", "ja"));
        assertTrue(SubtitleSpeechEmissionPolicy.meaningfulChange(
                "i know", "i know it's good", "en"));
        assertTrue(SubtitleSpeechEmissionPolicy.meaningfulChange(
                "こんに", "こんにちは", "ja"));
    }

    @Test public void stableSubstringsNeverEmitHalfUnicodeSurrogates() {
        assertEquals("こんにちは😀",
                SubtitleSpeechEmissionPolicy.stablePartial(
                        "こんにちは😀", "こんにちは😀", "ja"));
        assertEquals("こんにちは",
                SubtitleSpeechEmissionPolicy.stablePartial(
                        "こんにちは😀A", "こんにちは😀B", "ja").substring(0, 5));
    }
}
