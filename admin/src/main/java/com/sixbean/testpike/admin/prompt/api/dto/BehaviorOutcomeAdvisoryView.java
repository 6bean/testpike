package com.sixbean.testpike.admin.prompt.api.dto;

import java.math.BigDecimal;

/**
 * Admin mirror of core BehaviorOutcomeAdvisory ( item 7 gray-rollout
 * advisory; nine fields kept whole, no cross-module reference of core types —
 * the PromptTemplateView double-mirror precedent applies). The bridge relays
 * the verdict without re-judging it: thresholds, rate maths and the rollback
 * suggestion all live in core alone.
 *
 * @param templateDomain business domain the verdict belongs to
 * @param controlVersionId template row id used as the comparison control
 * @param candidateVersionId template row id under review (the gray release)
 * @param controlRate control version's observed result rate, NULL without data
 * @param candidateRate candidate version's observed result rate, NULL without data
 * @param dropPercentagePoints control minus candidate in percentage points, NULL without data
 * @param thresholdPercentagePoints tolerance applied by core this evaluation
 * @param rollbackAdvisory true when core suggests rolling the gray release back
 * @param note human-readable reason for the verdict
 */
public record BehaviorOutcomeAdvisoryView(
        String templateDomain,
        Long controlVersionId,
        Long candidateVersionId,
        BigDecimal controlRate,
        BigDecimal candidateRate,
        BigDecimal dropPercentagePoints,
        int thresholdPercentagePoints,
        boolean rollbackAdvisory,
        String note) {
}
