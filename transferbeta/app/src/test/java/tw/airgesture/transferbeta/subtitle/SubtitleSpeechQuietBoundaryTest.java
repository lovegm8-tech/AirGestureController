package tw.airgesture.transferbeta.subtitle;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

public class SubtitleSpeechQuietBoundaryTest {
    @Test public void quietCanFinalizeWithoutVoskEndpoint() {
        List<String> lines = new ArrayList<>();
        SubtitleSpeechChunker c = new SubtitleSpeechChunker("en",
                (t, id, fin) -> lines.add(id + ":" + (fin ? "F" : "P") + ":" + t));
        c.onStable("how are you", 1000);
        c.onQuiet(1800);
        c.onQuiet(2500);
        assertEquals(2, lines.size());
        assertEquals("1:P:how are you", lines.get(0));
        assertEquals("1:F:how are you", lines.get(1));
        c.onStable("how are you i am okay", 2800);
        assertEquals("2:P:i am okay", lines.get(2));
    }
    @Test public void subsequentVoskFinalDoesNotRecreatePreviousRow() {
        List<String> lines = new ArrayList<>();
        SubtitleSpeechChunker c = new SubtitleSpeechChunker("en",
                (t, id, fin) -> lines.add(id + ":" + t));
        c.onStable("hello everyone", 300);
        c.onQuiet(1400);
        c.onFinal("hello everyone");
        assertEquals(2, lines.size());
        c.onStable("new topic now", 2400);
        assertEquals("3:new topic now", lines.get(2));
    }
    @Test public void modelMustNotBeForcedToEndEmptyOrBeforeAnyWords() {
        List<String> lines = new ArrayList<>();
        SubtitleSpeechChunker c = new SubtitleSpeechChunker("ja",
                (t, id, fin) -> lines.add(t));
        c.onQuiet(3000);
        c.onFinal("");
        assertTrue(lines.isEmpty());
    }
}
