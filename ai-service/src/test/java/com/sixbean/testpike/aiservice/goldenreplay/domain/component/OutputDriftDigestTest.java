package com.sixbean.testpike.aiservice.goldenreplay.domain.component;

import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Output-drift digest contract (golden-set replay side): two model outputs
 * count as "not drifted" only when their normalized forms are byte-equal
 * after whitespace normalization, and every digest is a lowercase 64-char
 * SHA-256 hex that the core drift ledger accepts as a pair/snapshot form.
 */
class OutputDriftDigestTest {

    @Test
    void whitespaceOnlyDifferencesNormalizeToTheSameDigest() {
        String a = "  step  one\n  step two \t\n\n";
        String b = "step one step two";
        assertEquals(OutputDriftDigest.digestOf(a), OutputDriftDigest.digestOf(b));
        assertEquals("step one step two", OutputDriftDigest.normalize(a));
    }

    @Test
    void nullAndBlankOutputsShareOneEmptyDigest() {
        assertEquals(OutputDriftDigest.digestOf(null), OutputDriftDigest.digestOf("   "));
        assertEquals(OutputDriftDigest.digestOf(""), OutputDriftDigest.digestOf(null));
    }

    @Test
    void differentContentProducesDifferentDigests() {
        assertNotEquals(OutputDriftDigest.digestOf("PASS"), OutputDriftDigest.digestOf("REJECT"));
    }

    @Test
    void digestsAreLowercaseSha256Hex() {
        String d = OutputDriftDigest.digestOf("anything");
        assertTrue(d.matches("[0-9a-f]{64}"), "digest shape contract for the core ledger: " + d);
    }

    @Test
    void packDigestPinsSampleSetIdentityAndFollowsContent() {
        List<JudgeSampleBrief> pack = List.of(
                new JudgeSampleBrief(1L, "in-1", "exp-1", List.of(), 0),
                new JudgeSampleBrief(2L, "in-2", "exp-2", List.of(), 0));
        String first = OutputDriftDigest.packDigest(pack);
        assertEquals(first, OutputDriftDigest.packDigest(List.of(pack.get(1), pack.get(0))),
                "pack digest must be order-insensitive over the pinned sample set");
        List<JudgeSampleBrief> changed = List.of(
                new JudgeSampleBrief(1L, "in-1", "exp-1!", List.of(), 0),
                new JudgeSampleBrief(2L, "in-2", "exp-2", List.of(), 0));
        assertNotEquals(first, OutputDriftDigest.packDigest(changed));
    }
}
