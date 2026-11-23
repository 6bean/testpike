package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.SystemDraftInput;
import com.sixbean.testpike.core.aichat.api.dto.SystemDraftMintInput;

/**
 * Posting seam for system-origin AI drafts .
 * <p>Only the generic confirmation door downstream decides whether a
 * proposal ever writes; this service merely turns a machine proposal into a
 * pending ledger row so a human can confirm or ignore it — the AI never
 * gets a self-apply path.
 */
public interface SystemDraftService {

    /**
     * File one system-origin proposal as a pending draft.
     *
     * @param input project anchor + kind + distilled entry + traceability anchors
     * @return the ledger row id, confirmable through /internal/ai-chat/drafts/{id}/*
     */
    long postSystemDraft(SystemDraftInput input);

    /**
 * Mint (or re-arm in place) the single confirmable draft card keyed by a
 * linkage impact row . The key is the ledger, not
 * a time window: an identical re-post of the same snapshot must not reach
 * this method (the proposal store short-circuits replays), a newer base
 * version rewrites the one row, and a card that already landed is never
 * reopened.
 *
 * @param input project + kind + entity anchor + payload cut against a base version
 * @return the ledger row id of the minted or re-armed card
 */
    long mintLinkageDraft(SystemDraftMintInput input);

    /**
 * File one system-origin proposal whose confirmable payload is a
 * structured JSON document (WE-S6 census migration, piece 3).
 * Same guards as {@link #postSystemDraft} — real project anchor and a
 * kind registered for landing, both fail-closed — but the payload
 * rides verbatim instead of being wrapped into the distilled-entry
 * shape, so the landing strategy reads the proposal itself.
 *
 * @param projectId the owning project anchor
 * @param objectType registered draft kind
 * @param summary human-readable card headline
 * @param payloadJson structured proposal the landing strategy consumes
 * @param targetId existing object anchor, NULL for create-shaped cards
 * @return the ledger row id, confirmable through the generic door
 */
    long postStructuredDraft(Long projectId, String objectType, String summary,
                             String payloadJson, Long targetId);
}
