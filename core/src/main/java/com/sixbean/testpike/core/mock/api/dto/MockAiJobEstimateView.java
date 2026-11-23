package com.sixbean.testpike.core.mock.api.dto;

/**
 * Estimate view for one AI drafting job — pure arithmetic over the stored
 * prompt and the per-point output quota constants. Reported to the operator
 * BEFORE any LLM token is burned (estimate-first-then-run discipline); writing
 * nothing and calling no model is part of the contract.
 *
 * @param jobId                 the job this estimate describes
 * @param jobType               application point (quota follows the point)
 * @param estimatedInputTokens  heuristic input-token estimate from prompt size
 * @param outputQuotaTokens     per-point output ceiling constant
 * @param estimatedTotalTokens  input estimate + output quota (worst case)
 */
public record MockAiJobEstimateView(
        Long jobId,
        String jobType,
        int estimatedInputTokens,
        int outputQuotaTokens,
        int estimatedTotalTokens) {
}
