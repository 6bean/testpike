package com.sixbean.testpike.core.behavior.api.dto;

import java.math.BigDecimal;

/**
 * Verdict of comparing a candidate template version's observed result rate
 * against its control, used by the D4 gray channel to suggest (never perform)
 * a rollback when the candidate degrades beyond the tolerance.
 *
 * @param controlRate                control version's rate, NULL without data
 * @param candidateRate              candidate version's rate, NULL without data
 * @param dropPercentagePoints       control minus candidate in pp, NULL without data
 * @param thresholdPercentagePoints  tolerance actually applied this evaluation
 * @param rollbackAdvisory           true when the drop exceeds the tolerance
 * @param note                       human-readable reason for the verdict
 */
public record BehaviorOutcomeAdvisory(
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
