package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.plans.api.dto.SkipItem;

import java.util.List;

/**
 * Batch startup preflight check tiering results (shared component output, per specification semantics).
 *
 * @param approvedCaseIds case IDs entering the execution queue (review-approved and not isolated).
 * @param skips full skip list (written to skip_cases_json, including flaky and status-mismatch cases).
 * @param unevaluatedSkips status-mismatch items (block mode only blocks these).
 * @param flakySkips isolated flaky items (do not block startup in any mode).
 * @param blockedByUnevaluated true when block mode has status-mismatch skips (caller rejects with 400).
 */
public record BatchPrecheckResult(List<Long> approvedCaseIds, List<SkipItem> skips,
                                  List<SkipItem> unevaluatedSkips, List<SkipItem> flakySkips,
                                  boolean blockedByUnevaluated) {
}
