package com.sixbean.testpike.core.linkage.api.dto;

/**
 * Snapshot a proposal producer posts back for one armed grouped-draft impact
 * (core internal endpoint {@code POST /internal/linkage-proposals}). Exactly
 * one of the content shapes must be present: a usable draft carries
 * {@code proposalJson}; an unusable run is recorded honestly with
 * {@code rejectReason} instead of vanishing (a silent absence would make the
 * pending draft look ignorable forever).
 *
 * @param impactId          tp_linkage_impact row this snapshot answers to
 * @param downstreamObjectType  e.g. {@code script}
 * @param downstreamObjectId    the downstream row id
 * @param proposalJson      validated repaired action JSON (null for a rejected run)
 * @param schemaVersion     action JSON schema version of the proposal
 * @param modelName         model that produced the proposal (null for a rejected run)
 * @param baseScriptVersion downstream row version the draft was computed against
 * @param rejectReason      plain reason when no trustworthy draft could be produced
 */
public record LinkageProposalInput(
        Long impactId,
        String downstreamObjectType,
        Long downstreamObjectId,
        String proposalJson,
        String schemaVersion,
        String modelName,
        Integer baseScriptVersion,
        String rejectReason) {
}
