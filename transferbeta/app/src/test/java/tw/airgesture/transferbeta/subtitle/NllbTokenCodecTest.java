package tw.airgesture.transferbeta.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

/** Pure JVM regression tests for NLLB-200 framing, independent of model downloads. */
public final class NllbTokenCodecTest {
    private static final NllbTokenCodec.SentencePiece FAKE = new NllbTokenCodec.SentencePiece() {
        @Override public int[] encode(String text) {
            if (!"hello".equals(text)) throw new IllegalArgumentException(text);
            return new int[] { 4, 10, 0, 1, 2 };
        }
        @Override public String decode(int[] ids) { return Arrays.toString(ids); }
    };

    @Test public void knownLanguageTagsHaveStableIds() {
        assertEquals(256001 + 45, NllbTokenCodec.languageId("eng_Latn"));
        assertEquals(256001 + 79, NllbTokenCodec.languageId("jpn_Jpan"));
        assertEquals(256001 + 200, NllbTokenCodec.languageId("zho_Hant"));
        assertEquals(NllbTokenCodec.languageId("zho_Hant"), NllbTokenCodec.targetTraditionalChineseId());
    }

    @Test public void inputAddsLanguageEosAndAttentionMask() throws Exception {
        NllbTokenCodec.Input input = NllbTokenCodec.encode(FAKE, "eng_Latn", "hello");
        assertArrayEquals(new long[] { NllbTokenCodec.languageId("eng_Latn"), 5, 11, 3, 0, 2, 2 }, input.ids);
        assertArrayEquals(new long[] { 1, 1, 1, 1, 1, 1, 1 }, input.attentionMask);
    }

    @Test public void decodingSkipsLanguageMarkerAndStopsAtEos() throws Exception {
        assertEquals("[4, 10]", NllbTokenCodec.decode(FAKE,
            new int[] { NllbTokenCodec.targetTraditionalChineseId(), 5, 11, 2, 99 }));
    }

    @Test(expected = IllegalStateException.class)
    public void unavailableTokenizerNeverSilentlyFallsBack() throws Exception {
        NllbTokenCodec.encode(null, "eng_Latn", "hello");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownLanguageRejected() {
        NllbTokenCodec.languageId("xx_Unknown");
    }
}
