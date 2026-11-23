package com.sixbean.testpike.admin.aichat.api.dto;

import java.util.List;

/**
 * Request body of the group draft-apply call (AM batch piece 2b; 02
 * POST /api/v1/ai/chat/drafts/group/{messageId}/apply). The body carries the
 * ticket plus the ids the human actually checked — proposal anchors and
 * payloads never round-trip through the browser, so a forged request cannot
 * aim any write.
 *
 * @param confirmToken the one-shot group ticket returned by the group
 * confirm-token door; missing/blank/expired/replayed =
 * human-readable refusal with zero writes
 * @param draftIds the checked subset of this answer's pending cards;
 * ids outside the group are refused as a shape error
 * BEFORE the ticket is burned
 */
public record GroupDraftApplyRequest(String confirmToken, List<Long> draftIds) {
}
