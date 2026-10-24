package tw.airgesture.transferbeta.subtitle;

import static org.junit.Assert.*;
import org.junit.Test;

public class SubtitleTwoLineBufferTest {
    @Test public void firstSecondThirdSentencesRollExactlyTwoRows() {
        SubtitleTwoLineBuffer b = new SubtitleTwoLineBuffer();
        b.putVoice(1, "這是第一句");
        assertEquals(1, b.count());
        assertEquals("這是第一句", b.first());
        assertEquals("", b.second());
        b.putVoice(2, "這是第二句");
        assertEquals(2, b.count());
        assertEquals("這是第一句", b.first());
        assertEquals("這是第二句", b.second());
        b.putVoice(3, "這是第三句");
        assertEquals(2, b.count());
        assertEquals("這是第二句", b.first());
        assertEquals("這是第三句", b.second());
        b.putVoice(4, "這是第四句");
        assertEquals("這是第三句", b.first());
        assertEquals("這是第四句", b.second());
    }

    @Test public void partialFinalRevisesSameRowWithoutDuplicating() {
        SubtitleTwoLineBuffer b = new SubtitleTwoLineBuffer();
        b.putVoice(1, "我知道");
        b.putVoice(1, "我知道，這很好");
        assertEquals(1, b.count());
        assertEquals("我知道，這很好", b.first());
        b.putVoice(2, "第二句暫譯");
        b.putVoice(2, "第二句完整譯文");
        assertEquals(2, b.count());
        assertEquals("我知道，這很好", b.first());
        assertEquals("第二句完整譯文", b.second());
    }

    @Test public void firstRowCanBeCorrectedAfterSecondStarts() {
        SubtitleTwoLineBuffer b = new SubtitleTwoLineBuffer();
        b.putVoice(1, "第一句初稿");
        b.putVoice(2, "第二句初稿");
        b.putVoice(1, "第一句最終版本");
        assertEquals("第一句最終版本", b.first());
        assertEquals("第二句初稿", b.second());
        b.putVoice(3, "第三句");
        assertFalse(b.putVoice(1, "不能讓第一句復活"));
        assertEquals("第二句初稿", b.first());
        assertEquals("第三句", b.second());
    }

    @Test public void ocrFallbackAlsoUsesOnlyTwoLines() {
        SubtitleTwoLineBuffer b = new SubtitleTwoLineBuffer();
        b.putOcr("Hello", "你好");
        b.putOcr("Hello", "您好");
        assertEquals(1, b.count());
        b.putOcr("Welcome", "歡迎");
        b.putOcr("Goodbye", "再見");
        assertEquals("歡迎", b.first());
        assertEquals("再見", b.second());
    }

    @Test public void longSubtitleNeverIntroducesAdditionalLineBreaks() {
        SubtitleTwoLineBuffer b = new SubtitleTwoLineBuffer();
        b.putVoice(1, "中文第一行\n尾句");
        b.putVoice(2, "中文第二行   尾句");
        assertEquals("中文第一行 尾句", b.first());
        assertEquals("中文第二行 尾句", b.second());
        assertEquals(2, b.count());
    }

    @Test public void duplicateAndBlankResultsAreIgnored() {
        SubtitleTwoLineBuffer b = new SubtitleTwoLineBuffer();
        assertFalse(b.putVoice(1, ""));
        assertTrue(b.putVoice(1, "第一句"));
        assertFalse(b.putVoice(1, "第一句"));
        assertFalse(b.putVoice(2, " "));
        assertEquals(1, b.count());
        b.clear();
        assertEquals(0, b.count());
        assertEquals("", b.first());
    }

    @Test public void aiRevisionOnlyTouchesExistingLine() {
        SubtitleTwoLineBuffer b = new SubtitleTwoLineBuffer();
        b.putVoice(1, "語音第一句");
        b.putVoice(2, "語音第二句");
        assertTrue(b.reviseVoice(2, "AI 修正第二句"));
        assertEquals("語音第一句", b.first());
        assertEquals("AI 修正第二句", b.second());
        b.putVoice(3, "語音第三句");
        assertFalse(b.reviseVoice(1, "過時的 AI 修正"));
        assertEquals("AI 修正第二句", b.first());
        assertEquals("語音第三句", b.second());
    }

    @Test public void ocrAiCorrectionDoesNotReintroduceEvictedText() {
        SubtitleTwoLineBuffer b = new SubtitleTwoLineBuffer();
        b.putOcr("Hello", "你好");
        b.putOcr("Welcome", "歡迎");
        assertTrue(b.reviseOcr("Hello", "您好"));
        assertEquals("您好", b.first());
        b.putOcr("Thank you", "謝謝你");
        assertFalse(b.reviseOcr("Hello", "過時中文"));
        assertEquals("歡迎", b.first());
        assertEquals("謝謝你", b.second());
        assertEquals("歡迎", b.previousOfOcr("Thank you"));
    }
}
