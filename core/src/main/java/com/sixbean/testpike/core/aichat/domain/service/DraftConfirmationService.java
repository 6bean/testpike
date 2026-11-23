package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftGroupApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.aichat.api.dto.PendingGroupCardView;

import java.util.List;

/**
 * Confirmation chain of dialog AI drafts (AO batch stage 1, generalized
 * from the repair-only chain of stage 3b .
 * <p>The single door through which ANY AI write proposal may reach the
 * business ledger. The draft rows live in one generic table
 * (tp_ai_chat_draft) discriminated by object_type; this service runs the
 * shared guard order for every kind and dispatches the actual write to the
 * per-kind {@link DraftWriteStrategy}. Red line 6: a new proposal kind
 * registers a strategy bean — it never gets a second confirmation chain.
 * <p>Red-line invariant carried over unchanged: without a confirmed ticket
 * nothing is ever written, and a proposal can never self-apply.
 */
public interface DraftConfirmationService {

    /**
 * Re-tick the checked subset of a pending draft's candidate snapshot
 * : the door runs the same guard order as confirmation
 * (exists -> ownership -> pending) and then only flips {@code selected}
 * flags inside the payload — names outside the server-side candidates
 * refuse the whole edit, values never enter the payload, and a replay of
 * the already-current set writes nothing. Returns the resulting check count.
 */
    int updateCheckedSubset(Long operatorId, long draftId, List<String> selectedNames);

    /**
     * Issue the one-shot confirmation ticket for a pending draft.
     * Guard order (refusal spectrum of the bare door): draft exists (40401) →
     * session owned by the caller (40301) → draft still pending (40904) →
     * ticket lands in Redis → audit draft-confirm-issue.
     */
    DraftTicket issueConfirmToken(Long operatorId, long draftId);

    /**
     * Apply a confirmed draft through its kind's write strategy.
     * Guard order: draft exists (40401) → session owned (40301) → still
     * pending (40904) → ticket consumed (40001, one-shot GETDEL) → strategy
     * re-checks its own optimistic anchor (40901 stale proposal writes
     * nothing) → CAS flip status=2 with applied trace columns → audit
     * draft-confirm-apply.
     */
    DraftApplyResult apply(Long operatorId, long draftId, String confirmToken);

    /**
 * Apply with the human's explicit either-or choice for dual-payload drafts
 * (create vs update carried on the same card, AL batch ). The door
 * never guesses: a dual-payload draft refuses confirmation unless the
 * caller names the chosen action; single-payload kinds ignore it.
 */
    default DraftApplyResult apply(Long operatorId, long draftId, String confirmToken,
                                   String selectedAction) {
        return apply(operatorId, draftId, confirmToken);
    }

    /**
 * Issue the one-shot ticket for a GROUP confirmation (AM batch
 * piece 2, IT-ITR-05): the ticket binds the answer message id, and the
 * pending cards of that answer were walked through the same per-row
 * guards (existence -> session ownership -> pending) before issue.
 */
    DraftTicket issueGroupConfirmToken(Long operatorId, long messageId);

    /**
     * Apply the human-checked subset of one answer's cards under one group
     * ticket, in ONE transaction. Phase A adjudicates every checked card
     * against the target's current version: disjoint proposals silently
     * re-anchor, truly colliding ones turn stale, are named in the receipt
     * and leave the write set; phase B lands the rest and then renews the
     * pending siblings of each touched target once (the group's own checked
     * ids are excluded, so no card of this answer can double-flip). The
     * ticket is one-shot and a post-consume refusal burns it on purpose.
     */
    DraftGroupApplyResult applyGroup(Long operatorId, long messageId, String confirmToken,
                                     List<Long> selectedDraftIds);

    /**
 * Scope-generalized group ticket : the ticket
 * binds the DraftGroupScope wire instead of a bare answer id, so
 * system-origin groups (one project, one object kind — the posture the
 * linkage bus mints grouped_draft impacts in) open the SAME door and
 * guard order as dialog answers. The message-keyed methods above
 * delegate here; nothing else sees a second door.
 */
    DraftTicket issueGroupConfirmTokenByScope(Long operatorId, String groupScope);

    /** Scope counterpart of {@link #applyGroup}: same guards, same receipt shape. */
    DraftGroupApplyResult applyGroupByScope(Long operatorId, String groupScope,
                                            String confirmToken, List<Long> selectedDraftIds);

    /**
 * Read-only listing of one group's pending cards (AN batch piece
 * D4): the pinned panel shows the to-be-disposed list BEFORE a ticket
 * exists, so the human can survey and check a subset first. Every guard
 * of the issue door applies per row (origin-aware ownership, strict
 * scope parse) and the read burns nothing; it differs in exactly one
 * respect — an empty group returns an empty list instead of refusing,
 * because a panel legitimately shows "nothing pending".
 */
    List<PendingGroupCardView> pendingGroupCards(Long operatorId, String groupScope);
}
