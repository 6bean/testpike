package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Mirror of the core draft-card fold ({@code ChatDraftInfo}) — the sealed
 * history door carries each landed proposal on its own message so the reloaded
 * page can render and confirm the card. Admin maps nothing: the shape is the
 * core ledger's own.
 *
 * @param id             draft ledger row id (addresses the confirm/rollback doors)
 * @param objectType     draft kind discriminator
 * @param summary        one-line card title from landing time
 * @param payloadJson    proposal body the card renders from
 * @param status         ledger state (1 pending / 2 applied)
 * @param targetId       row the proposal acts on (null for creates)
 * @param baseVersion    optimistic anchor the proposal was cut against
 *                       (null for creates; the confirm door re-checks it)
 * @param appliedVersion version receipt recorded when the write landed
 * @param rolledBack     once-only rollback mark from the draft ledger
 */
public record ChatDraftView(
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
