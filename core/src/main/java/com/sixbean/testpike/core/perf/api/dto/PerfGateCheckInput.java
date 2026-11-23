package com.sixbean.testpike.core.perf.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <p>pressureModel/pressureConfig = U-5 budget preview pair (optional): when the
 * wizard passes the declared pressure, the decision rides along the server-computed
 * dispatch budget (same {@code PerfBudgetPolicy} single source the dispatcher writes);
 * legacy 5-arg callers (A-1 launch enforcement) keep zero = "no budget claim".
 *
 * @param pressureModel U-5 budget preview input (nullable = undeclared, preview yields 0)
 * @param pressureConfig U-5 pressure keys (nullable; CONSTANT_RPS/SPIKE=durationSeconds, RAMP=ramp+hold)
 */
public record PerfGateCheckInput(Long projectId, Long envId, Integer concurrency,
                                 String isolationMode, String targetBaseUrl,
                                 String pressureModel, JsonNode pressureConfig) {

    /** Compatibility constructor for callers that declare no pressure (launch enforcement). */
    public PerfGateCheckInput(Long projectId, Long envId, Integer concurrency,
                              String isolationMode, String targetBaseUrl) {
        this(projectId, envId, concurrency, isolationMode, targetBaseUrl, null, null);
    }
}
