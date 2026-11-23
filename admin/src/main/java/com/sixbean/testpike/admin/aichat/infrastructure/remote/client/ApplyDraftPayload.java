package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

/**
 * Wire body of the draft-apply call : only the
 * one-shot ticket crosses the boundary — the case anchor and action JSON stay
 * core-side in the confirmable snapshot, so no client can aim the write.
 */
public record ApplyDraftPayload(String confirmToken, String selectedAction) {
}
