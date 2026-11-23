package com.sixbean.testpike.core.events.api.dto;

/**
 * One aggregated run row of the process event ledger (tp_ai_task_event):
 * all frames landed under one run token, folded into a single entry for the
 * history-list revisit door (R67). {@code eventCount} is the frame total,
 * {@code hasError} tells whether any frame of the run carried the error
 * level, and the first/last timestamps bound when the run was alive. Rows
 * landed without a run token never show up as a run. Newest run first —
 * the same shape every revisit door in this ledger already rides.
 */
public record TaskEventRunRow(
        String runToken,
        long eventCount,
        boolean hasError,
        String firstAt,
        String lastAt) {
}
