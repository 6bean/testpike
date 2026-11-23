package com.sixbean.testpike.admin.aichat.app.service;

import com.sixbean.testpike.admin.aichat.api.dto.EvolutionProposalsMirrorView;
import com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;

/**
 * Manual trigger door of the self-evolution proposal run (AF-i phase 2b-ii).
 * <p>The run lands pending draft cards, so this door is the write-side twin of
 * {@link EvolutionSignalsAdminService}: same permission posture, no new
 * spectrum. All semantic rules (month shape, empty-window honesty, stale-batch
 * exclusion, negative gate) stay single-sourced in core.
 */
public interface EvolutionProposalAdminService {

    /**
 * Fire one proposal generation for a project segment.
 *
 * @param user the logged-in human who owns the evolution session
 * @param month yyyy-MM window key (validated fail-closed core-side)
 * @param projectId the segment to run against; mandatory — enforced here
 * @return the cards that were landed (empty when the window had no evidence)
 */
    EvolutionProposalsMirrorView generate(CurrentUser user, String month, Long projectId);

    /**
     * One-click rollback of one landed proposal card (AF-i phase 3). The door
     * pins the login identity as the operator — the same posture as the draft
     * confirmation chain: who may act on the card (session ownership), whether
     * it landed, and whether its family has a version chain are all guarded
     * single-sourced in core, so no manager pre-filter belongs here.
     *
     * @param user    the logged-in human who must own the evolution session
     * @param draftId the applied EVOLUTION_PROPOSAL card to undo
     * @return the copied-forward template version (rollbackTo names its source)
     */
    EvolutionRollbackMirrorView rollback(CurrentUser user, long draftId);
}
