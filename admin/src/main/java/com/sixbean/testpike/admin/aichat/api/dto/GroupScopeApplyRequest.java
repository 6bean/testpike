package com.sixbean.testpike.admin.aichat.api.dto;

import java.util.List;

/**
 * Body walking through the scope-shaped group door (AN batch piece D4;
 * 02 POST /api/v1/ai/chat/drafts/group/apply). Same discipline as
 * {@link GroupDraftApplyRequest}: only the group coordinates, the ticket and
 * the checked ids ride the wire — proposal anchors and payloads never round-
 * trip through the browser, so a forged request cannot aim any write.
 *
 * @param projectId project the group is pinned to (viewer segment guard)
 * @param objectType draft kind discriminator of the group
 * @param confirmToken one-shot group ticket from the scope confirm-token door
 * @param draftIds checked subset of the group's pending cards; ids
 * outside the group are refused as a shape error BEFORE
 * the ticket is burned (core-side, single source)
 */
public record GroupScopeApplyRequest(long projectId, String objectType,
                                     String confirmToken, List<Long> draftIds) {
}
