package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * stepsTimeline detail walk (WE batch S3 , single source R40): the
 * executor journals one timeline row per executed action and carries the
 * machine payload (candidate pools, artifact notes) in the row's `detail`.
 * Two consumers need the same read — the exploration loop (passing the
 * element-candidates pool to core for anchor reconciliation) and the G4
 * page-inspect bridge (building the prompt candidate segment) — so the walk
 * lives here once instead of being copied into each caller.
 * Fail-soft by contract: malformed or absent timelines yield null, which
 * every consumer must read as "no evidence", never as an empty verdict.
 */
public final class ExploreTaskTimeline {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExploreTaskTimeline() {
    }

    /** First non-blank detail recorded for the given action; null when absent. */
    public static String detailFor(String stepsTimelineJson, String action) {
        if (stepsTimelineJson == null || stepsTimelineJson.isBlank()
                || action == null || action.isBlank()) {
            return null;
        }
        try {
            JsonNode timeline = MAPPER.readTree(stepsTimelineJson);
            if (!timeline.isArray()) {
                return null;
            }
            for (JsonNode step : timeline) {
                if (!action.equals(step.path("action").asText(null))) {
                    continue;
                }
                String detail = step.path("detail").asText(null);
                if (detail != null && !detail.isBlank()) {
                    return detail;
                }
            }
            return null;
        } catch (Exception malformed) {
            return null;
        }
    }
}
