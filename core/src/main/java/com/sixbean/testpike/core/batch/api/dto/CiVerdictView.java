package com.sixbean.testpike.core.batch.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Structured CI gate verdict view (V5/PRD; GET /open/ci/runs/{batchId}/verdict).
 * <p>The single source of truth for verdicts is {@code CiGatePolicy} (red line 6: the pass rate references PassRateSql.RATE_EXPR and shares the same source as the badge).
 * No second formula is allowed; each api/core endpoint only wraps it rather than copying it.
 *
 * @param batchId batch id.
 * @param verdict conclusion: pass / fail / pending (not yet in a terminal state or no terminal-state tasks).
 * @param verdictPhrase human-readable verdict (pending includes the Chinese display name of the batch status or no terminal-state tasks).
 * @param passRate pass rate 0~1 (same source and value as the badge; NULL when pending means no hard verdict).
 * @param threshold effective threshold (K8 key ci.verdict.pass-rate defaults to 0.95 and can be overridden by plan exit_criteria).
 * @param thresholdSource threshold source: config=K8 key / plan=regression plan exit-criteria override.
 * @param unmetItems contains details of unmet items, empty when pass and containing {item:passRate,required,actual} when fail.
 * It also contains per-item entries {item:p0Fail,caseId,title,errorDetail}.
 */
public record CiVerdictView(long batchId, String verdict, String verdictPhrase,
                            BigDecimal passRate, BigDecimal threshold, String thresholdSource,
                            List<Map<String, Object>> unmetItems) {
}
