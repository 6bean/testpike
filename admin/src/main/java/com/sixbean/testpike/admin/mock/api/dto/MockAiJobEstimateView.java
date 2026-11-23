package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Pre-run cost estimate view (admin bridge; mirror of the core
 * MockAiJobEstimateView record). The operator sees the price before any
 * token burns — estimate-first-then-run discipline, arithmetic only.
 */
public record MockAiJobEstimateView(
        Long jobId,
        String jobType,
        int estimatedInputTokens,
        int outputQuotaTokens,
        int estimatedTotalTokens) {
}
