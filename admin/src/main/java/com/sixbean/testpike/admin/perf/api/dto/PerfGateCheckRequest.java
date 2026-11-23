package com.sixbean.testpike.admin.perf.api.dto;

/**
 *
 * @param pressureModel U-5 budget preview input (nullable = no declaration, budget comes back 0)
 * @param pressureConfig U-5 pressure keys (nullable; same shape the save payload sends to core)
 */
public record PerfGateCheckRequest(Long projectId, Long envId, Integer concurrency,
                                   String isolationMode, String targetBaseUrl,
                                   String pressureModel, Object pressureConfig) {
}
