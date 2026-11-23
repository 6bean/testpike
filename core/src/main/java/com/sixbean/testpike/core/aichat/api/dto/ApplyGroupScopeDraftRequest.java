package com.sixbean.testpike.core.aichat.api.dto;

import java.util.List;

/**
 * Request body walking through the scope-shaped group door (AN batch
 * piece D4; POST /internal/ai-chat/drafts/group/apply). Same discipline as
 * {@link ApplyGroupDraftRequest}: only the ticket and the checked ids ride
 * the wire — proposal anchors and payloads never round-trip through the
 * caller, so a forged request cannot aim any write.
 *
 * @param groupScope canonical DraftGroupScope wire the ticket was minted for
 * (a ticket minted for another group is refused and burned)
 * @param confirmToken the one-shot group ticket from the scope confirm-token door
 * @param draftIds the checked subset of the group's pending cards; ids
 * outside the group are refused as a shape error BEFORE
 * the ticket is burned
 */
public record ApplyGroupScopeDraftRequest(String groupScope, String confirmToken,
                                          List<Long> draftIds) {
}
