package com.sixbean.testpike.core.webelement.api.dto;

import java.time.LocalDateTime;

/**
 * One self-heal evidence row as stored in tp_exec_step_heal_evidence (V554).
 * Verbatim shape of what the executor relayed with the result envelope
 * (03 protocol 2.4 trailing key stepHealEvidence) — the read side adds no
 * scoring or derived numbers, so both consumer surfaces (report step badges
 * and the Page Element Library "recent heals" list) read one single source.
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
