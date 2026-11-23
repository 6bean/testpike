package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.common.linkage.LinkageDraftImpact;
import com.sixbean.testpike.common.linkage.LinkageImpactInfo;
import com.sixbean.testpike.common.linkage.LinkagePendingImpacts;

import java.util.List;

/**
 * Read side of the downstream impact ledger .
 * The dispatch service only writes {@code tp_linkage_impact}; this service is
 * the single way anyone reads it back for display. Pending rows (status
 * stale-mark / draft-pending / disclosed, never consumed) are what the
 * grouped-card panel and the pinned-panel badge count — consumed rows stay
 * queryable through {@code findPendingByUpstream}'s exclusion only, they are
 * history, not work.
 */
public interface LinkageImpactQueryService {

    /**
     * Pending impacts of one upstream object, oldest first (stable card order).
     *
     * @param upstreamObjectType bus token of the saved object, e.g. {@code case}
     * @param upstreamObjectId   the object that was saved
     * @return rows whose consumed_at is still null; empty when the save found
     *         zero downstream intersections (the silent zero-impact account of
     *         TC-LNK-07 — no rows, no cards, nothing to disturb the user with)
     */
    List<LinkageImpactInfo> findPendingByUpstream(String upstreamObjectType, long upstreamObjectId);

    /**
 * Same pending ledger answer plus the ownership anchor the permission
 * gate needs : the owning project is read from the
 * upstream object row itself — the caller's projectId is never trusted.
 *
 * @param upstreamObjectType bus token of the saved object, e.g. {@code case}
 * @param upstreamObjectId the object that was saved
 * @return rows plus their upstream object's project; projectId null only
 * for an empty account (nothing to disclose an owner of)
 * @throws ServiceException when the account holds rows whose upstream
 * object no longer names a project, or the kind has no ownership
 * path at all — refused loudly, never answered as an empty list
 */
    LinkagePendingImpacts findPendingViewByUpstream(String upstreamObjectType, long upstreamObjectId);

    /**
 * Live ledger anchors for a set of draft cards ( segment D③, the
 * grouped-card read seam): each draft whose {@code context_json} carries
 * a {@code linkageImpactId} answers with that impact row read afresh —
 * including a settled row's {@code consumed_at}, so the card discloses
 * the current ledger state instead of a copy frozen at mint time.
 * Drafts with no linkage anchor are honestly absent from the answer,
 * unknown ids yield nothing, and an empty or null list never touches
 * the ledger.
 *
 * @param draftIds the draft cards currently rendered in the group
 * @return one anchor row per linked draft, stable by draft id
 */
    List<LinkageDraftImpact> findImpactsByDrafts(List<Long> draftIds);
}
