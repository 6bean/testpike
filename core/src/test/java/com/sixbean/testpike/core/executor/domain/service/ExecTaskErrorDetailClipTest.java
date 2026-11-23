package com.sixbean.testpike.core.executor.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine failure details travel verbatim from the worker, but the task table
 * column is a fixed-width varchar: an over-long detail must be clipped to the
 * column budget server-side (the write path used to pass it raw, blowing the
 * UPDATE into a data-integrity error that stalled the run at status=1).
 * The clip keeps the tail (the fatal last lines of an engine log matter more
 * than its header) and marks what was dropped so the reader is not misled
 * into treating a fragment as the whole log.
 */
class ExecTaskErrorDetailClipTest {

    private static final int TASK_COLUMN_WIDTH = 1000;

    @Test
    void nullAndShortDetailsPassThroughUntouched() {
        assertNull(ExecTaskServiceImpl.clipToTaskDetailColumn(null));
        String shortDetail = "connection refused";
        assertEquals(shortDetail, ExecTaskServiceImpl.clipToTaskDetailColumn(shortDetail));
        String exact = "x".repeat(TASK_COLUMN_WIDTH);
        assertEquals(exact, ExecTaskServiceImpl.clipToTaskDetailColumn(exact),
                "a detail at exactly the column budget must not be rewritten");
    }

    @Test
    void overlongDetailKeepsTailAndMarksTruncationWithinBudget() {
        String longDetail = "H".repeat(9_000) + "|TAIL-ERROR org.apache.jmeter.report...";
        String clipped = ExecTaskServiceImpl.clipToTaskDetailColumn(longDetail);
        assertTrue(clipped.length() <= TASK_COLUMN_WIDTH,
                "clip must fit the varchar(1000) column, got " + clipped.length());
        assertTrue(clipped.endsWith("|TAIL-ERROR org.apache.jmeter.report..."),
                "the tail (fatal lines) must survive the clip");
        assertTrue(clipped.startsWith("[truncated, ") && clipped.contains(" chars dropped] "),
                "the drop marker must state how much is missing, got: " + clipped.substring(0, 60));
        int stated = Integer.parseInt(clipped.substring("[truncated, ".length(),
                clipped.indexOf(" chars dropped] ")));
        assertEquals(longDetail.length() - (clipped.length()
                - "[truncated, ".length() - String.valueOf(stated).length()
                - " chars dropped] ".length()), stated,
                "the stated drop count must match what the returned string actually kept");
    }
}
