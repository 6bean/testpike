package com.sixbean.testpike.admin.webelement.api.dto;

import java.time.LocalDateTime;

/**
 * Admin mirror of the core self-heal evidence row (tp_exec_step_heal_evidence,
 * V554). Field-for-field: the relay chain stores verbatim, the browser reads
 * the same shape (03 protocol 2.4 trailing key stepHealEvidence).
 */
public record StepHealEvidenceView(
        Long id,
        Long taskId,
        Long elementId,
        Integer stepNo,
        Boolean healed,
        Boolean firstAnchorFailed,
        String fromAnchorType,
        String fromAnchorValue,
        String usedAnchorType,
        String usedAnchorValue,
        String viewport,
        Integer ledgerRevision,
        Double similarityScore,
        LocalDateTime createdAt) {
}
