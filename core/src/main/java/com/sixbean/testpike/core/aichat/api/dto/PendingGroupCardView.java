package com.sixbean.testpike.core.aichat.api.dto;

/**
 * One pending card of a group, as the read side of the group door lists it
 * (AN batch piece D4: the pinned panel needs the to-be-disposed list
 * BEFORE any ticket exists, so the human can check a subset first).
 * <p>Read-only view over the same rowset the group door walks
 * (tp_ai_chat_draft columns) — proposal bodies are never invented here,
 * and nothing on this view authorises a write: only the ticket doors do.
 *
 * @param draftId tp_ai_chat_draft id (the id the checked-subset apply names)
 * @param projectId project the card is anchored to (system-origin groups pin it
 * on the ledger; dialog rows are guarded through their session)
 * @param objectType draft kind discriminator (DraftObjectType wire name)
 * @param summary one-line card title written at mint time
 * @param targetId row the proposal acts on (null for create-style kinds)
 * @param baseVersion optimistic anchor the proposal was cut against (null when
 * the kind has no version chain — the door re-validates server-side)
 * @param schemaVersion payload schema the card renders under
 * @param payloadJson proposal body the card renders from
 * @param status ledger state (1 = pending confirmation; the read only lists pending)
 */
public record PendingGroupCardView(
        long draftId,
        long projectId,
        String objectType,
        String summary,
        Long targetId,
        Integer baseVersion,
        String schemaVersion,
        String payloadJson,
        int status) {
}
