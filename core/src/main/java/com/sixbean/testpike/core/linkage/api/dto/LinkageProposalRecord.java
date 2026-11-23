package com.sixbean.testpike.core.linkage.api.dto;

import java.time.LocalDateTime;

/**
 * One proposal snapshot read back from {@code tp_linkage_proposal} — the
 * durable form of what the AI draft generator produced for an armed
 * grouped-draft impact, waiting for (or already consumed by) the one-click
 * human accept.
 *
 * @param id                  row id
 * @param impactId            ledger row this snapshot answers to
 * @param downstreamObjectType  e.g. {@code script}
 * @param downstreamObjectId    the downstream row id
 * @param proposalJson        validated repaired action JSON (null for a rejected run)
 * @param schemaVersion       action JSON schema version of the proposal
 * @param modelName           model that produced the proposal
 * @param baseScriptVersion   downstream row version the draft was computed against
 * @param status              1=pending, 2=consumed, 3=rejected
 * @param rejectReason        plain reason of an unusable proposal (null otherwise)
 * @param createdAt           when the snapshot landed
 * @param updatedAt           when the snapshot last changed
 */
public record LinkageProposalRecord(
        Long id,
        Long impactId,
        String downstreamObjectType,
        Long downstreamObjectId,
        String proposalJson,
        String schemaVersion,
        String modelName,
        Integer baseScriptVersion,
        Integer status,
        String rejectReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
