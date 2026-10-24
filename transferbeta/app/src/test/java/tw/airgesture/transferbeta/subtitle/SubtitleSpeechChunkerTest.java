package tw.airgesture.transferbeta.subtitle;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

public final class SubtitleSpeechChunkerTest {
    @Test public void longEnglishWithoutVoskFinalCreatesSecondSubtitleRow() {
        List<String> events = new ArrayList<>();
        SubtitleSpeechChunker chunks = new SubtitleSpeechChunker("en",
                (text, id, fin) -> events.add(id + ":" + (fin ? "F" : "P") + ":" + text));
        chunks.onStable("hello everyone i want to", 1000);
        chunks.onStable("hello everyone i want to explain the", 1600);
        chunks.onStable("hello everyone i want to explain the issue", 2550);
        assertTrue(events.contains("1:F:hello everyone i want to explain the issue"));
        chunks.onStable("hello everyone i want to explain the issue because it", 2800);
        assertTrue(events.stream().anyMatch(e -> e.startsWith("2:P:because it")));
        chunks.onFinal("hello everyone i want to explain the issue because it matters");
        assertTrue(events.stream().anyMatch(e -> e.startsWith("2:F:because it matters")));
        assertFalse(events.stream().anyMatch(e -> e.startsWith("3:P:")));
    }

    @Test public void shortSpeechEmitsOneRowAndFinalCanRepeatPartial() {
        List<String> events = new ArrayList<>();
        SubtitleSpeechChunker chunks = new SubtitleSpeechChunker("en",
                (text, id, fin) -> events.add(id + ":" + (fin ? "F" : "P") + ":" + text));
        chunks.onStable("hello there", 100);
        chunks.onFinal("hello there");
        chunks.onStable("where are you", 1200);
        assertEquals("1:P:hello there", events.get(0));
        assertEquals("1:F:hello there", events.get(1));
        assertEquals("2:P:where are you", events.get(2));
    }

    @Test public void japaneseAlsoSplitsLongRun() {
        List<String> events = new ArrayList<>();
        SubtitleSpeechChunker chunks = new SubtitleSpeechChunker("ja",
                (text, id, fin) -> events.add(id + ":" + (fin ? "F" : "P") + ":" + text));
        chunks.onStable("今日は天気がとてもよくて外に出かけたい", 100);
        chunks.onStable("今日は天気がとてもよくて外に出かけたい", 1700);
        chunks.onStable("今日は天気がとてもよくて外に出かけたい気分だ", 1900);
        assertTrue(events.stream().anyMatch(e -> e.startsWith("1:F:")));
        assertTrue(events.stream().anyMatch(e -> e.startsWith("2:P:")));
    }

    @Test public void revisedOldPrefixMustNotReplayAnAlreadyConsumedRow() {
        List<String> events = new ArrayList<>();
        SubtitleSpeechChunker chunks = new SubtitleSpeechChunker("en",
                (text, id, fin) -> events.add(id + ":" + (fin ? "F" : "P") + ":" + text));
        chunks.onStable("we should really finish this right now", 10);
        chunks.onStable("we should really finish this right now", 1500);
        int n = events.size();
        chunks.onStable("we should really finish", 1800);
        assertEquals(n, events.size());
    }
}
