package com.sixbean.testpike.core.document.api.dto;

/**
 * One process step of a running extraction, echoed from the tp_ai_task_event
 * ledger . {@code cursor}
 * is the stream row id — strictly increasing, so a client tails by
 * remembering the last cursor it saw; replaying that cursor returns exactly
 * the same next window. {@code level} is one of the viewer's filter values
 * (thinking / step / error); {@code detail} carries the machine-readable part
 * (token counts, stage codes, failure codes) as stored; {@code landedAt} is
 * the ISO local timestamp string shown in the tail.
 */
public record ExtractionEventRow(
        long cursor,
        String level,
        String summary,
        String detail,
        String landedAt) {
}
