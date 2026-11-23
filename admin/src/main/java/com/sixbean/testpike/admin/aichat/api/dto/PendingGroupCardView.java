package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * One pending card of a group as the read side of the scope door lists it
 * .
 * Field-for-field mirror of the core internal projection over the same
 * tp_ai_chat_draft rowset — the panel renders the to-be-disposed list
 * BEFORE any ticket exists; nothing on this view authorises a write.
 *
 * @param draftId tp_ai_chat_draft id the checked-subset apply names
 * @param projectId project the card is anchored to
 * @param objectType draft kind discriminator
 * @param summary one-line card title written at mint time
 * @param targetId row the proposal acts on (null for create-style kinds)
 * @param baseVersion optimistic anchor the proposal was cut against
 * @param schemaVersion payload schema the card renders under
 * @param payloadJson proposal body the card renders from
 * @param status ledger state (the read only lists pending cards)
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
