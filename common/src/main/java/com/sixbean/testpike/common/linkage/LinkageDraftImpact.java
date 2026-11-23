package com.sixbean.testpike.common.linkage;

/**
 * One grouped-card row paired with its live-read ledger anchor (
 * segment D③). The grouped card must never render a stale anchor copied out
 * of the draft payload: every open reads the {@code tp_linkage_impact} row
 * behind the card afresh, so the user sees the ledger state at the moment
 * of the click (who was impacted, at which upstream version, and whether a
 * consumption already landed — {@code impact.consumedAt}).
 *
 * @param draftId the draft card this anchor belongs to
 * @param projectId owning project read from the draft row itself — the
 * bridge gate resolves the owner here and never trusts a
 * caller-supplied segment
 * @param impact the ledger row behind the card (shared contract from
 * {@code common/linkage}, single source since the
 * mirror-DTO consolidation)
 */
public record LinkageDraftImpact(Long draftId, Long projectId, LinkageImpactInfo impact) {
}
