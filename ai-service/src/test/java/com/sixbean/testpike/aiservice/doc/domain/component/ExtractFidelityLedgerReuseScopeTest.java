package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger.Entry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reuse-scoped fidelity retention (ticket 1): a generation that
 * answers buckets from the cross-run bucket-result cache burns zero
 * provider calls for the unchanged content, yet the content IS read-backed —
 * a proven earlier generation booked its own full-denominator row. The old
 * arithmetic scored such a reuse leg as retention 6714/13481 = 49.80 (the
 * live false red of run 710db3de) because the reused scope stayed invisible.
 *
 * <p>Fixed account: retention = (injected + reused) / source. A leg with no
 * reuse books zero and keeps the whole-document denominator face byte-
 * identically (the floor semantics stay welded — this package never
 * loosens a fresh full-document round), while a reuse leg measures how much
 * of the document this generation covers by either reading or reusing.
 */
class ExtractFidelityLedgerReuseScopeTest {

    private static final long DOC = 9085L;
    private static final String TOKEN = "gen-reuse-scope";
    private static final String TASK_TYPE = ExtractFidelityLedger.TASK_DOC_EXTRACT;

    private static final PlaneLabels LABELS =
            new PlaneLabels("extract-plane", "summary-plane", "gap-plane");

    private static Entry book(int injected, int sourceTokens, int reusedTokens) {
        ExtractFidelityMeter meter = new ExtractFidelityMeter();
        if (injected > 0) {
            meter.record(LABELS.extractPlane(), injected, 200);
        }
        meter.bookReusedSourceTokens(reusedTokens);
        return ExtractFidelityLedger.entry(DOC, TOKEN, TASK_TYPE, sourceTokens, 94, 4,
                12, meter, 617, 564, injected, LABELS);
    }

    @Test
    void reuseLegCountsReusedScopeAsRead() {
        // The live numbers of run 710db3de: 6714 injected + 6767 reused = the
        // whole 13481-token document -> full coverage, not the old 49.80.
        Entry e = book(6714, 13481, 6767);
        assertEquals(6767, e.reusedSourceTokens());
        assertEquals(6714, e.injectedTokens(), "the call-log face never inflates");
        assertEquals(100.00, e.retentionPercent(), 0.01);
    }

    @Test
    void zeroReuseKeepsTheLegacyWholeDocumentDenominator() {
        // reused the arithmetic is byte-identical to the pre-fix face.
        Entry e = book(6714, 13481, 0);
        assertEquals(0, e.reusedSourceTokens());
        assertEquals(49.80, e.retentionPercent(), 0.01);
    }

    @Test
    void pureReuseLegAnswersFullCoverageWithZeroBurnedCalls() {
        Entry e = book(0, 13481, 13481);
        assertEquals(0, e.injectedTokens());
        assertEquals(100.00, e.retentionPercent(), 0.01);
    }

    @Test
    void zeroDenominatorWithoutReuseKeepsTheHonestZero() {
        // rule preserved: an empty denominator with no reuse face still
        // answers 0, never a fabricated green.
        Entry e = book(0, 0, 0);
        assertEquals(0.0, e.retentionPercent(), 0.01);
    }

    @Test
    void negativeReusedBookingIsIgnored() {
        Entry e = book(500, 1000, -7);
        assertEquals(0, e.reusedSourceTokens());
        assertEquals(50.0, e.retentionPercent(), 0.01);
    }
}
