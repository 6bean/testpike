package com.sixbean.testpike.common.linkage;

import java.util.List;

/**
 * Pending-impact ledger answer with its ownership anchor .
 * The impact table itself carries no project column by design (one ledger,
 * one truth); the owner is resolved here from the upstream object row so the
 * permission gate never has to trust a caller-supplied projectId.
 *
 * @param projectId owning project of the upstream object; null only when the
 * account is empty — an empty ledger has no owner to disclose
 * @param impacts pending rows, oldest first (stable grouped-card order)
 */
public record LinkagePendingImpacts(Long projectId, List<LinkageImpactInfo> impacts) {
}
