package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Fail-safe runId read of the perf dispatch envelope (task-badge deep link source).
 * <p>Gap this anchors: the execution-task list cannot point a perf row at its
 * run detail page because the runId lives only inside action_json and no
 * read-side extraction exists. {@code runIdOf} mirrors the {@code budgetMsOf}
 * discipline — any dirty/non-perf envelope returns null instead of throwing,
 * so the shared row mapper never breaks the list/claim main chain.
 * <p>Pure component test (no DB): the read point parses the envelope only.
 */
class PerfEnvelopeRunIdReadTest {

    private final PerfRunDispatchSupport support =
            new PerfRunDispatchSupport(null, new ObjectMapper(), null, null);

    /** Valid perf-run envelope → the positive runId it pins. */
    @Test
    void perfEnvelopeReturnsPinnedRunId() {
        assertEquals(4712L, support.runIdOf(
                "{\"kind\":\"perf-run\",\"runId\":4712,\"engine\":\"K6\",\"artifact\":\"a.zip\"}"));
    }

    /** Non-perf envelope kinds (case/tool/explore/apitest/appauto) → null, no throw. */
    @Test
    void nonPerfEnvelopeYieldsNull() {
        assertNull(support.runIdOf("{\"kind\":\"api-test-run\",\"runId\":9}"));
        assertNull(support.runIdOf("{\"action\":\"click\"}"));
    }

    /** Missing / non-positive runId → null (a badge without a target is honest absence). */
    @Test
    void missingOrNonPositiveRunIdYieldsNull() {
        assertNull(support.runIdOf("{\"kind\":\"perf-run\",\"engine\":\"K6\"}"));
        assertNull(support.runIdOf("{\"kind\":\"perf-run\",\"runId\":0}"));
        assertNull(support.runIdOf("{\"kind\":\"perf-run\",\"runId\":-3}"));
    }

    /** Dirty payload (blank / non-JSON / null) → null: the list row must never break. */
    @Test
    void dirtyEnvelopeYieldsNull() {
        assertNull(support.runIdOf("  "));
        assertNull(support.runIdOf("not-json"));
        assertNull(support.runIdOf(null));
    }
}
