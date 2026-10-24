package tw.airgesture.transferbeta.subtitle;

import static org.junit.Assert.*;
import org.junit.Test;

public class SubtitleVoiceQueuePolicyTest {
    @Test public void newerUtterancesWinOverOldPendingResults() {
        assertTrue(SubtitleVoiceQueuePolicy.replace(4, true, 5, false));
        assertFalse(SubtitleVoiceQueuePolicy.replace(5, false, 4, true));
    }
    @Test public void oldPartialNeverOverwritesPendingFinal() {
        assertFalse(SubtitleVoiceQueuePolicy.replace(7, true, 7, false));
        assertTrue(SubtitleVoiceQueuePolicy.replace(7, false, 7, true));
    }
    @Test public void identicalFinalMustNotBeDroppedAfterPreview() {
        assertFalse(SubtitleVoiceQueuePolicy.suppress(9, "hello there", false,
                9, "hello there", true));
        assertTrue(SubtitleVoiceQueuePolicy.suppress(9, "hello there", true,
                9, "hello there", true));
    }
    @Test public void newPhraseNeverWaitsBehindSubtitleThrottle() {
        assertEquals(0, SubtitleVoiceQueuePolicy.throttle(4, 5, false, 10));
        assertEquals(0, SubtitleVoiceQueuePolicy.throttle(4, 4, true, 10));
        assertEquals(200, SubtitleVoiceQueuePolicy.throttle(4, 4, false, 80));
        assertEquals(0, SubtitleVoiceQueuePolicy.throttle(4, 4, false, 900));
    }
}
