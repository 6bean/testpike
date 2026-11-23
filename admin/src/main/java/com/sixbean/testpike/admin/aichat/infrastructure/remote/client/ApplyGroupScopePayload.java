package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import java.util.List;

/**
 * Wire body walking the scope-shaped group door core-side (POST
 * /internal/ai-chat/drafts/group/apply, AN batch piece D4). Twin of the
 * core request record — ticket plus checked ids only, never proposal bodies.
 *
 * @param groupScope canonical group wire the ticket was minted for
 * @param confirmToken one-shot group ticket
 * @param draftIds checked subset of the group's pending cards
 */
public record ApplyGroupScopePayload(String groupScope, String confirmToken,
                                     List<Long> draftIds) {
}
