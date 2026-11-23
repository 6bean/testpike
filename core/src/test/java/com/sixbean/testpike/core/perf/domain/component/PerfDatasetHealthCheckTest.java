package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dataset pool health wording (CF-7, expected consumption vs capacity).
 * <p>The exclusive row-allocation strategies lease each data row once (queue
 * order or per-user segment — both consume one row per request), so a run at
 * peak rate for its declared duration needs rate x seconds rows. When that
 * exceeds the pool the operator must see the shortfall and the approximate
 * drain moment before launching, with a concrete remedy named. The safe
 * default (CYCLE) is annotated as never draining; an unresolvable rate or
 * duration claims nothing (null) instead of guessing.
 */
class PerfDatasetHealthCheckTest {

    @Test
    void cycleModeIsAnnotatedAsNeverDraining() {
        String note = PerfDatasetHealthCheck.noteOf(PerfRowAllocModes.CYCLE, 50, 200, 180);
        assertTrue(note.contains("循环复用"), note);
        assertTrue(note.contains("不会耗尽"), note);
    }

    @Test
    void exclusiveShortfallNamesDemandPoolSizeAndDrainMoment() {
        // 200 rps x 180s = 36000 rows demanded against a 50-row pool:
        // drain at floor(50 / 200 * ...) seconds — sub-second drain rounds to 0,
        // so the wording keeps a positive whole number only when truly derivable.
        String note = PerfDatasetHealthCheck.noteOf(
                PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE, 1000, 10, 300);
        assertTrue(note.contains("约消耗 3000 行"), note);
        assertTrue(note.contains("数据池 1000 行"), note);
        assertTrue(note.contains("100 秒"), note);
        assertTrue(note.contains("循环复用"), note);
    }

    @Test
    void vuserExclusiveSharesTheSameDemandFormula() {
        String note = PerfDatasetHealthCheck.noteOf(
                PerfRowAllocModes.VUSER_EXCLUSIVE, 100, 5, 100);
        assertTrue(note.contains("约消耗 500 行"), note);
        assertTrue(note.contains("20 秒"), note);
    }

    @Test
    void demandWithinPoolStaysSilent() {
        assertNull(PerfDatasetHealthCheck.noteOf(
                PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE, 5000, 10, 300));
        assertNull(PerfDatasetHealthCheck.noteOf(
                PerfRowAllocModes.VUSER_EXCLUSIVE, 500, 1, 500));
    }

    @Test
    void unknownModeOrUnresolvableShapeClaimsNothing() {
        assertNull(PerfDatasetHealthCheck.noteOf(null, 100, 10, 300));
        assertNull(PerfDatasetHealthCheck.noteOf("LEGACY_SPELLING", 100, 10, 300));
        assertNull(PerfDatasetHealthCheck.noteOf(
                PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE, 100, 0, 300));
        assertNull(PerfDatasetHealthCheck.noteOf(
                PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE, 100, 10, 0));
    }
}
