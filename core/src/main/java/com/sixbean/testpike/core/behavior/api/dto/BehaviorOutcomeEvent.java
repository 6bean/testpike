package com.sixbean.testpike.core.behavior.api.dto;

import java.math.BigDecimal;

/**
 * One real user verdict on AI-produced material, ready for the outcome ledger
 * . Immutable carrier between the decision channels (review,
 * verification chips, draft confirmation) and BehaviorOutcomeService.
 *
 * @param templateDomain prompt template domain that produced the material
 * @param templateVersionId pinned template version row id, NULL when unknown
 * @param scope what was judged: session, document or task
 * @param scopeRef business id of the judged object for trace-back
 * @param source decision channel: review, chip or draft
 * @param sourceEventId id of the verdict row inside its own channel — the
 * dedup key that makes replays count exactly once
 * @param outcome adopted, deprecated, corrected or partial
 * @param weight contribution to the version's result rate; NULL = 1.00
 * @param projectId owning project for bucketing (aggregate reporting only)
 */
public record BehaviorOutcomeEvent(
        String templateDomain,
        Long templateVersionId,
        String scope,
        String scopeRef,
        String source,
        String sourceEventId,
        String outcome,
        BigDecimal weight,
        Long projectId) {
}
