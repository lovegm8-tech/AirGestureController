package tw.airgesture.transferbeta.subtitle;

import static org.junit.Assert.*;
import org.junit.Test;

public class SubtitleAiOutputPolicyTest {
    @Test public void outputIsOneShortLineWithoutModelReasoning() {
        assertEquals("我正在等你。", SubtitleAiOutputPolicy.normalize(
                "<think>some reasoning</think>翻譯：\n「我正在等你。」"));
    }
    @Test public void chineseIsRequired() {
        assertFalse(SubtitleAiOutputPolicy.accept("just English", "原本字幕"));
        assertTrue(SubtitleAiOutputPolicy.accept("我現在就走。", "我走了。"));
    }
    @Test public void repetitiveOrLongHallucinationRejected() {
        assertFalse(SubtitleAiOutputPolicy.accept("原文：Hello", "你好"));
        assertFalse(SubtitleAiOutputPolicy.accept("我是很長很長很長很長很長很長很長的廢話", "你來了。"));
    }
    @Test public void malformedOrFragmentaryNllbNeverOverwritesQuickCaption() {
        assertFalse(SubtitleAiOutputPolicy.acceptRevision("はい、元気です。どこに？", "⁇ ,我很好.", "是的，我很好。在哪裡？"));
        assertFalse(SubtitleAiOutputPolicy.acceptRevision("Have to.", "我必須.", "必須。"));
        assertFalse(SubtitleAiOutputPolicy.acceptRevision("I would say therefore.", "我認為這是一個問題.", "因此，我會說。"));
        assertTrue(SubtitleAiOutputPolicy.acceptRevision("お元気ですか？", "你好嗎?", "你做得很好嗎？"));
    }

    @Test public void modelOutputNeverAddsNewCaptionRow() {
        SubtitleTwoLineBuffer captions = new SubtitleTwoLineBuffer();
        assertTrue(captions.putVoice(1, "原翻譯第一句"));
        assertTrue(captions.putVoice(2, "原翻譯第二句"));
        String revised = SubtitleAiOutputPolicy.normalize("「自然的第二句」");
        assertTrue(captions.putVoice(2, revised));
        assertEquals(2, captions.count());
        assertEquals("原翻譯第一句", captions.first());
        assertEquals("自然的第二句", captions.second());
        captions.putVoice(3, "新第三句");
        assertFalse(captions.putVoice(1, "過時的 AI 修正"));
        assertEquals("自然的第二句", captions.first());
    }
}
