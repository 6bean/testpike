package com.sixbean.testpike.admin.linkage.infrastructure.remote.client;

import com.sixbean.testpike.common.linkage.LinkageDraftImpact;
import com.sixbean.testpike.common.linkage.LinkagePendingImpacts;

import java.util.List;

/**
 * core downstream-impact ledger client .
 * Mirrors the internal seam one-for-one; no rule lives here — disposition
 * semantics stay in core (red line 6, one rule source).
 */
public interface CoreLinkageImpactClient {

    /**
     * Pending impacts of one upstream object, with the owning project
     * resolved by core from the upstream object row itself.
     */
    LinkagePendingImpacts pending(String upstreamType, long upstreamId);

    /** Live ledger anchors for a set of draft cards . */
    List<LinkageDraftImpact> byDrafts(List<Long> draftIds);
}
