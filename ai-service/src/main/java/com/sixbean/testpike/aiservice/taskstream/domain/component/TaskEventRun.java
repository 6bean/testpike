package com.sixbean.testpike.aiservice.taskstream.domain.component;

import java.time.LocalDateTime;

/**
 * One run of a long-running job, aggregated from its process event rows
 * (tp_ai_task_event grouped by run token). Feeds the history list behind a
 * revisit entry: the viewer shows when the run opened, when its newest frame
 * landed and how many frames exist, then replays any run through the shared
 * cursor window ({@link TaskProcessEventStore#fetchSince}). {@code hasError}
 * marks a run whose stream carries at least one error-level frame — the
 * failed badge of the history list.
 */
public record TaskEventRun(
        String runToken,
        long events,
        LocalDateTime firstAt,
        LocalDateTime lastAt,
        boolean hasError) {
}
