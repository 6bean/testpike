package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Request body of the draft-apply call (AO batch stage 3b ,
 * generalized in stage 1; 02 POST /api/v1/ai/chat/drafts/{id}/apply).
 * The body carries nothing but the ticket — proposal anchors never round-trip
 * through the browser, so a forged request cannot aim any write.
 *
 * @param confirmToken the one-shot ticket string returned by the confirm-token
 * door; missing/blank/expired/replayed = human-readable
 * refusal with zero writes
 */
public record DraftApplyRequest(String confirmToken, String selectedAction) {
}
