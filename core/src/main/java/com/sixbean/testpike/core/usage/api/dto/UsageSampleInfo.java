package com.sixbean.testpike.core.usage.api.dto;

/**
 *
 * @param cachedTokens E9 cache-read surface: prompt tokens the provider
 * served from its cache for this call (0 = no hit or
 * the provider did not report one). The AP-6 savings
 * numbers are settled by reading this column.
 */
public record UsageSampleInfo(long id, String model, String provider, String chain,
                              Long projectId, int estTokens, int usagePromptTokens,
                              Double deviationRate, long latencyMs, String createdAt,
                              String tokenizerEngine, int cachedTokens) {
}
