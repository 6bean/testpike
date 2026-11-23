package com.sixbean.testpike.aiservice.judge.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Latest job-plane row of the question-generation door ( follow-up,
 * . One row per run, keyed by run token: the polling face answers
 * status plus the cumulative progress of the sealed groups, so a caller can
 * render "group i of n, k items so far" without waiting for the whole run.
 * The status face is a closed set — running / done / failed — and a failed
 * row always carries the readable human cause.
 */
public record QaGenJobView(long documentId,
                           String runToken,
                           String status,
                           int totalGroups,
                           int doneGroups,
                           List<Map<String, Object>> items,
                           int promptTokens,
                           int completionTokens,
                           String model,
                           String errorMsg) {

    /** Run is still streaming groups into the row. */
    public static final String STATUS_RUNNING = "running";

    /** Every group landed and at least one usable question survived. */
    public static final String STATUS_DONE = "done";

    /** The run aborted; errorMsg names the cause in human words. */
    public static final String STATUS_FAILED = "failed";
}
