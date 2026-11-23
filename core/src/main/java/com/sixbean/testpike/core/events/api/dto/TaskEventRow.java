package com.sixbean.testpike.core.events.api.dto;

/**
 * One process step of a long-running AI job, read from the tp_ai_task_event
 * ledger through the single-source window door ( writer, S4 segment B
 * shared read). {@code cursor} is the ledger row id — strictly increasing, so
 * a viewer tails by remembering the last cursor it saw and replaying that
 * cursor returns exactly the same next window. {@code level} is one of the
 * viewer's closed filter set (thinking / step / error); {@code detail} carries
 * the machine-readable part (token counts, stage codes, failure codes) as
 * stored; {@code landedAt} is the ISO local timestamp string shown in the tail.
 */
public record TaskEventRow(
        long cursor,
        String level,
        String summary,
        String detail,
        String landedAt) {
}
