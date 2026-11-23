package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Latest job-plane row mirrored from ai-service QaGenJobView (question-generation
 * job plane, admin bridge). One row per run keyed by run token: the
 * polling face answers status plus the cumulative progress of the sealed
 * groups, so the viewer can render "group i of n, k items so far" without
 * waiting for the whole run. Status is the closed set running / done / failed;
 * a failed row always carries the readable cause in errorMsg.
 *
 * @param documentId source document of the run
 * @param runToken receipt token from the accepted submit
 * @param status running / done / failed
 * @param totalGroups sealed groups the run will walk
 * @param doneGroups groups landed so far
 * @param items cumulative usable questions in group order
 * @param promptTokens summed input tokens booked by the run
 * @param completionTokens summed output tokens booked by the run
 * @param model model that actually answered
 * @param errorMsg human cause on a failed row, null otherwise
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
}
