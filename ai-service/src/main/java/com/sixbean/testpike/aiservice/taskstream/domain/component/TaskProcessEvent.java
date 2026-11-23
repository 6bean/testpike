package com.sixbean.testpike.aiservice.taskstream.domain.component;

import java.time.LocalDateTime;

/**
 * One process step of a long-running AI job, as landed in tp_ai_task_event.
 * <p>{@code cursor} is the stream row id: strictly increasing per run, so the
 * viewer tails by remembering the last cursor it saw and replaying it is
 * idempotent. {@code level} is one of the viewer's filter values
 * (thinking / step / error). {@code detail} is an optional JSON blob with the
 * machine-readable part of the step (token counts, parsed totals, retry
 * causes); {@code summary} is the one-line human text shown in the tail.
 */
public record TaskProcessEvent(
        long cursor,
        String level,
        String summary,
        String detail,
        LocalDateTime landedAt) {
}
