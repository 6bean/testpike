package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Request body of the draft-apply call (AO batch stage 3b ;
 * 02 POST /internal/ai-chat/drafts/{id}/apply).
 *
 * @param confirmToken the one-shot ticket string returned by the confirm-token
 * door; missing/blank/expired/replayed = human-readable
 * refusal with zero writes
 * @param selectedAction the human's either-or choice on a dual-payload
 * requirement card ("create"/"update"); kinds without a
 * dual shape ignore it
 */
public record ApplyDraftRequest(String confirmToken, String selectedAction) {
}
