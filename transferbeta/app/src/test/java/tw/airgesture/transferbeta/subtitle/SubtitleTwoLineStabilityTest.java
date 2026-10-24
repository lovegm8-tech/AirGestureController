package tw.airgesture.transferbeta.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleTwoLineStabilityTest {
    @Test public void delayedAiCannotResurrectEvictedSentence() {
        SubtitleTwoLineBuffer buffer = new SubtitleTwoLineBuffer();
        assertTrue(buffer.putVoice(1, "第一句"));
        assertTrue(buffer.putVoice(2, "第二句"));
        assertTrue(buffer.putVoice(3, "第三句"));
        assertFalse(buffer.reviseVoiceIfUnchanged(1, "第一句", "過期 AI"));
        assertEquals("第二句", buffer.first());
        assertEquals("第三句", buffer.second());
    }

    @Test public void sameUtteranceRevisesWithoutScrolling() {
        SubtitleTwoLineBuffer buffer = new SubtitleTwoLineBuffer();
        assertTrue(buffer.putVoice(1, "預覽"));
        assertTrue(buffer.putVoice(1, "完整翻譯"));
        assertEquals(1, buffer.count());
        assertEquals("完整翻譯", buffer.first());
        assertFalse(buffer.reviseVoiceIfUnchanged(1, "預覽", "過期修正"));
        assertTrue(buffer.reviseVoiceIfUnchanged(1, "完整翻譯", "AI 定稿"));
        assertEquals("AI 定稿", buffer.first());
    }

    @Test public void staleVoiceCannotRotateLatestRows() {
        SubtitleTwoLineBuffer buffer = new SubtitleTwoLineBuffer();
        buffer.putVoice(10, "十");
        buffer.putVoice(11, "十一");
        buffer.putVoice(12, "十二");
        assertFalse(buffer.putVoice(10, "遲到的十"));
        assertEquals("十一", buffer.first());
        assertEquals("十二", buffer.second());
    }
}
