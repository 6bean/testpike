package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the R63 three-element refusal wording of the perf ingest
 * doors (metric frames batch, per-frame field validation, replay collision,
 * event stream rows, run-state gates). Every sentence must echo the
 * offending value, quote a literal repair entry or protocol field in 「」,
 * and carry an action word — the same shape the error-guidance gate checks
 * mechanically, pinned here without booting infrastructure.
 */
class PerfIngestRefusalsL2Test {

    private static void assertThreeElements(String text, String... echoedValues) {
        for (String value : echoedValues) {
            assertTrue(text.contains(value), "offending value not echoed: " + value + " in [" + text + "]");
        }
        assertTrue(text.contains("请"), "missing action word 请: " + text);
        assertTrue(text.matches(".*「[^」]+」.*"), "missing literal repair entry in 「」: " + text);
    }

    @Test
    void echoesBatchSizeAndCapWhenFramesExceedLimit() {
        assertThreeElements(PerfIngestRefusals.batchTooLong(9000, 7200), "9000", "7200");
    }

    @Test
    void echoesNegativeSecondOffset() {
        assertThreeElements(PerfIngestRefusals.secondOffsetIllegal(3, -1), "3", "-1", "secondOffset");
    }

    @Test
    void echoesOutOfRangeErrorRate() {
        assertThreeElements(PerfIngestRefusals.errorRateIllegal(2, 1.5), "2", "1.5", "errorRate");
    }

    @Test
    void echoesNegativeRequestCount() {
        assertThreeElements(PerfIngestRefusals.reqCountIllegal(4, -7), "4", "-7", "reqCount");
    }

    @Test
    void echoesBothCountsWhenFailExceedsRequest() {
        assertThreeElements(PerfIngestRefusals.failExceedsRequest(5, 9, 3), "5", "9", "3");
    }

    @Test
    void echoesNegativeDurationSum() {
        assertThreeElements(PerfIngestRefusals.durationSumIllegal(6, -200), "6", "-200", "durationSumMs");
    }

    @Test
    void echoesAnchorOfDuplicatedFrameInBatch() {
        assertThreeElements(PerfIngestRefusals.duplicateAnchorInBatch(7, 42, "下单"), "7", "42", "下单");
    }

    @Test
    void echoesBothThroughputsOnReplayMismatch() {
        assertThreeElements(PerfIngestRefusals.replayMismatch(8, 42, "下单", 12.5, 30.0),
                "8", "42", "12.5", "30.0", "runId");
    }

    @Test
    void namesEventKindFieldWhenBlank() {
        assertThreeElements(PerfIngestRefusals.eventKindBlank(1), "1", "eventKind");
    }

    @Test
    void echoesNegativeEventOffset() {
        assertThreeElements(PerfIngestRefusals.eventOffsetIllegal(2, -5), "2", "-5");
    }

    @Test
    void echoesDetailLengthAgainstCap() {
        assertThreeElements(PerfIngestRefusals.eventDetailTooLong(3, 9000, 4000), "3", "9000", "4000");
    }

    @Test
    void echoesRunAndProjectIdsWhenRunMissing() {
        assertThreeElements(PerfIngestRefusals.runNotFound(66L, 8L), "66", "8", "runId");
    }

    @Test
    void echoesRunIdWhenRunAlreadyFinished() {
        assertThreeElements(PerfIngestRefusals.runFinished(66L), "66");
    }
}
