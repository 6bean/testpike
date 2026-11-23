package com.sixbean.testpike.core.aichat.api.dto;

/**
 * System-origin draft posting input .
 * <p>The distilled project-memory channel (ai-service scheduler) has no chat
 * session, so the proposal is anchored to a project instead: {@code content}
 * is the distilled entry text, {@code sourceRef} the traceability anchors
 * ([{type,id}] json) shown on the ledger card. {@code targetId} anchors the
 * proposal to the pre-created awaiting-confirmation memory row so the ledger
 * panel can route the operator to this card ; dialog-origin
 * drafts leave it NULL. The confirmation door's system branch later guards
 * WHO may promote it (login, membership, project-memory key) — this seam
 * only decides WHAT becomes confirmable.
 */
public record SystemDraftInput(Long projectId, String objectType, String content,
                               String sourceRef, Long targetId) {
}
