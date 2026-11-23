package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Body opening the scope-shaped group confirm door (AN batch piece D4;
 * 02 POST /api/v1/ai/chat/drafts/group/confirm-token). The pair
 * (projectId, objectType) names a system-origin group minted by the linkage
 * bus — the same coordinates the pinned panel already carries — so the
 * browser never hand-builds the group wire. Shape adjudication stays
 * single-sourced in core; this carrier only names the segment.
 *
 * @param projectId project the group is pinned to (viewer segment guard runs on it)
 * @param objectType draft kind discriminator of the group (e.g. SCRIPT_REPAIR)
 */
public record GroupScopeConfirmRequest(long projectId, String objectType) {
}
