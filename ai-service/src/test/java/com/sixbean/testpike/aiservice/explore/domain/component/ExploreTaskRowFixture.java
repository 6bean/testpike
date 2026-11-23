package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared builder for the stubbed ai-task result row used by the explore-kernel
 * tests. Extracted so the kernel regressions and the site-survey branch tests
 * consume one row shape instead of keeping two copies in sync (single source
 * for the task-row fixture envelope).
 */
final class ExploreTaskRowFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExploreTaskRowFixture() {
    }

    /**
     * Builds the full response envelope {@code {"code":"0","msg":"ok","data":{...}}}
     * for a finished explore task.
     *
     * @param status            terminal task status value
     * @param errorDetail       error detail text (null when none)
     * @param stepsTimelineJson serialized steps timeline array
     * @param screenshotsJson   serialized screenshots array (or key-only variant)
     * @param durationMs        reported run duration
     * @param startedAt         row start timestamp literal
     * @param finishedAt        row finish timestamp literal
     * @param createdAt         row create timestamp literal
     */
    static String taskRowJson(int status, String errorDetail, String stepsTimelineJson,
                              String screenshotsJson, long durationMs,
                              String startedAt, String finishedAt, String createdAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", 9001);
        data.put("projectId", 1);
        data.put("caseId", 0);
        data.put("executorId", 5);
        data.put("status", status);
        data.put("claimToken", "t-9001");
        data.put("schemaVersion", "explore-v1");
        data.put("actionJson", "{}");
        data.put("envBaseUrl", null);
        data.put("envVersionSnapshot", null);
        data.put("errorType", null);
        data.put("errorDetail", errorDetail);
        data.put("stepsTimeline", stepsTimelineJson);
        data.put("screenshots", screenshotsJson);
        data.put("videoKey", null);
        data.put("durationMs", durationMs);
        data.put("log", "");
        data.put("startedAt", startedAt);
        data.put("finishedAt", finishedAt);
        data.put("createdAt", createdAt);
        try {
            return "{\"code\":\"0\",\"msg\":\"ok\",\"data\":"
                    + MAPPER.writeValueAsString(data) + "}";
        } catch (Exception e) {
            throw new IllegalStateException("task-row fixture assembly failed", e);
        }
    }
}
