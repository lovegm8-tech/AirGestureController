package tw.airgesture.transferbeta.subtitle;
import org.junit.Test;
import static org.junit.Assert.*;
public class SubtitlePipelineTraceTest {
    @Test public void keepsAtMostSixtyEvents() {
        SubtitlePipelineTrace trace = new SubtitlePipelineTrace();
        for (int i = 0; i < 90; i++) trace.record(i * 1000L, "ASR", "event" + i);
        assertEquals(60, trace.size());
        assertFalse(trace.snapshot().contains("event0"));
        assertTrue(trace.snapshot().contains("event89"));
        trace.clear();
        assertEquals(0, trace.size());
    }
    @Test public void normalizesLineBreaksAndBoundsText() {
        SubtitlePipelineTrace trace = new SubtitlePipelineTrace();
        trace.record(1234L, "OCR", "hello\n" + "a".repeat(250));
        String result = trace.snapshot();
        assertTrue(result.contains("hello "));
        assertFalse(result.contains("\n"));
        assertTrue(result.length() < 210);
    }
}
