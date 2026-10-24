package tw.airgesture.transferbeta.subtitle;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleAiStaleResultTest {
    @Test public void rejectsPreviousAiVersion() {
        SubtitleTwoLineBuffer buffer = new SubtitleTwoLineBuffer();
        assertTrue(buffer.putVoice(10, "初稿"));
        assertTrue(buffer.putVoice(10, "更新稿"));
        assertFalse(buffer.reviseVoiceIfUnchanged(10, "初稿", "舊 AI"));
        assertEquals("更新稿", buffer.first());
        assertTrue(buffer.reviseVoiceIfUnchanged(10, "更新稿", "新 AI"));
    }
    @Test public void rejectsEvictedOcrRevision() {
        SubtitleTwoLineBuffer buffer = new SubtitleTwoLineBuffer();
        buffer.putOcr("source", "譯文");
        buffer.putVoice(1, "一句");
        buffer.putVoice(2, "二句");
        assertFalse(buffer.reviseOcrIfUnchanged("source", "譯文", "過期"));
    }
}
