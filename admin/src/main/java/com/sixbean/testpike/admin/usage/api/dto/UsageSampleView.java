package com.sixbean.testpike.admin.usage.api.dto;

/**
 *
 * @param cachedTokens E9 cache reading for the row (0 = no hit or not
 * reported by the provider; both states render)
 */
public record UsageSampleView(long id, String model, String provider, String chain,
                              Long projectId, int estTokens, int usagePromptTokens,
                              Double deviationRate, long latencyMs, String createdAt,
                              int cachedTokens) {
}
