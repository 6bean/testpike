package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Confirmable draft card as it reads back from the draft ledger
 * (self-evolution loop, closed-card visibility): a card landed during the
 * live stream must survive a page reload, otherwise the proposal is booked
 * but unconfirmable. The fold rides on the message it belongs to — one entry
 * per tp_ai_chat_draft row, never a second query face for the client.
 *
 * @param id             tp_ai_chat_draft id (addresses the confirm/rollback doors)
 * @param objectType     draft kind discriminator (DraftObjectType wire name)
 * @param summary        one-line card title written at landing time
 * @param payloadJson    proposal body the card renders from
 * @param status         ledger state (1 pending confirmation / 2 applied)
 * @param targetId       row the proposal acts on (null for creates)
 * @param baseVersion    optimistic anchor the proposal was cut against
 *                       (null for creates and kinds without a version chain —
 *                       the confirm door re-validates against it server-side)
 * @param appliedVersion version receipt recorded when the write landed
 *                       (null until applied — never a client-side claim)
 * @param rolledBack     once-only mark from the rollback stamp: an applied
 *                       proposal already moved back never offers rollback twice
 */
public record ChatDraftInfo(
        long id,
        String objectType,
        String summary,
        String payloadJson,
        int status,
        Long targetId,
        Integer baseVersion,
        Integer appliedVersion,
        boolean rolledBack) {
}
