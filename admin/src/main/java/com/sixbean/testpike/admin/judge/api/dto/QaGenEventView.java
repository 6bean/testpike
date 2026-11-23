package com.sixbean.testpike.admin.judge.api.dto;

/**
 * One process step of a running question-generation job, mirrored from the
 * ai-service task-event frame (same field shape as ExtractionEventView so the
 * shared frontend tail client renders both streams with one decoder, R40).
 * {@code cursor} is the strictly increasing row id — replaying the last seen
 * cursor returns exactly the same window.
 *
 * @param cursor   replay cursor (row id)
 * @param level    viewer filter value: thinking / step / error
 * @param summary  one-line human text shown in the tail
 * @param detail   optional machine-readable JSON part (token counts, causes)
 * @param landedAt ISO local timestamp of the landed row
 */
public record QaGenEventView(long cursor, String level, String summary,
                             String detail, String landedAt) {
}
