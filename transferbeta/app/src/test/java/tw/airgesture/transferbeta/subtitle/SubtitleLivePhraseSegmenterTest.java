package tw.airgesture.transferbeta.subtitle;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class SubtitleLivePhraseSegmenterTest {
    @Test public void partialUpdatesSameRowAndQuietClosesOnlyThatPhrase() {
        SubtitleLivePhraseSegmenter s = new SubtitleLivePhraseSegmenter("en");
        List<SubtitleLivePhraseSegmenter.Phrase> p =
                s.partial("first ten minutes", 1000);
        assertEquals(1, p.size());
        assertEquals(1L, p.get(0).id);
        assertFalse(p.get(0).isFinal);
        p = s.partial("first ten minutes you are doing great", 1800);
        assertEquals(1, p.size());
        assertEquals(1L, p.get(0).id);
        p = s.silence(3000);
        assertEquals(1, p.size());
        assertEquals(1L, p.get(0).id);
        assertTrue(p.get(0).isFinal);
        assertTrue(s.silence(4000).isEmpty());
        p = s.partial("first ten minutes you are doing great next sentence begins", 4400);
        assertEquals(1, p.size());
        assertEquals(2L, p.get(0).id);
        assertEquals("next sentence begins", p.get(0).source);
        assertEquals(0, s.finish("first ten minutes you are doing great next sentence begins", 5100)
                .stream().filter(v -> v.id == 1L).count());
    }

    @Test public void longContinuousSpeechCreatesNewRowsWithoutVoskEndpoint() {
        SubtitleLivePhraseSegmenter s = new SubtitleLivePhraseSegmenter("en");
        s.partial("one two three four", 1000);
        List<SubtitleLivePhraseSegmenter.Phrase> events =
                s.partial("one two three four five six seven eight nine ten eleven twelve", 3900);
        assertEquals(2, events.size());
        assertEquals(1L, events.get(0).id);
        assertEquals(2L, events.get(1).id);
        assertTrue(events.get(1).source.startsWith("nine"));
        List<SubtitleLivePhraseSegmenter.Phrase> finalEvents =
                s.finish("one two three four five six seven eight nine ten eleven twelve", 5000);
        assertEquals(1, finalEvents.size());
        assertEquals(2L, finalEvents.get(0).id);
        assertEquals("nine ten eleven twelve", finalEvents.get(0).source);
    }

    @Test public void japaneseBreaksLongTextAndStillUsesOneRowPerPhrase() {
        SubtitleLivePhraseSegmenter s = new SubtitleLivePhraseSegmenter("ja");
        String longSpeech = "これは日本語の動画字幕です、次の内容について説明していきます。";
        s.partial("これは日本語の動画字幕です", 1000);
        List<SubtitleLivePhraseSegmenter.Phrase> events = s.partial(longSpeech, 4100);
        assertEquals(2, events.size());
        assertEquals(1L, events.get(0).id);
        assertEquals(2L, events.get(1).id);
    }

    @Test public void noRepeatedFinalAfterQuietAndVoskEndpoint() {
        SubtitleLivePhraseSegmenter s = new SubtitleLivePhraseSegmenter("en");
        s.partial("hello everyone", 1000);
        s.silence(2200);
        assertTrue(s.finish("hello everyone", 2800).isEmpty());
        List<SubtitleLivePhraseSegmenter.Phrase> next =
                s.partial("this is a new sentence", 3700);
        assertEquals(1, next.size());
        assertEquals(3L, next.get(0).id);
    }

    @Test public void tinyAndEmptyPartialDoesNotRotate() {
        SubtitleLivePhraseSegmenter s = new SubtitleLivePhraseSegmenter("en");
        assertTrue(s.partial("", 1000).isEmpty());
        assertTrue(s.silence(2000).isEmpty());
        assertEquals(1L, s.partial("hello", 2500).get(0).id);
    }
}
