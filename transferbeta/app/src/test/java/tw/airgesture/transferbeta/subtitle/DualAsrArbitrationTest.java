package tw.airgesture.transferbeta.subtitle;

import org.junit.Test;
import static org.junit.Assert.*;

public class DualAsrArbitrationTest {
    @Test public void stablePreviewRequiresRepeatedTranscript() {
        assertFalse(DualAsrArbitration.stablePreview("I think", "I think we should"));
        assertTrue(DualAsrArbitration.stablePreview("I think we should", "I think we should"));
    }
    @Test public void identicalFinalDoesNotCreateRevision() {
        assertTrue(DualAsrArbitration.samePhrase("I think we should", "I think we should"));
        assertFalse(DualAsrArbitration.shouldRevise("I think we should", "I think we should"));
    }
    @Test public void unrelatedSentencesAreNotMerged() {
        assertFalse(DualAsrArbitration.samePhrase("Please come here", "Tomorrow is sunny"));
        assertFalse(DualAsrArbitration.samePhrase("", "Tomorrow is sunny"));
    }
    @Test public void punctuationCanBeRevisedWithinSamePhrase() {
        assertTrue(DualAsrArbitration.shouldRevise("I think we should go", "I think we should go now"));
    }
}
