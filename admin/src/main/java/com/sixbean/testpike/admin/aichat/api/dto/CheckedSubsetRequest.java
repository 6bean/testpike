package com.sixbean.testpike.admin.aichat.api.dto;

import java.util.List;

/**
 * Body of PATCH /api/v1/ai/chat/drafts/{id}/checked-subset :
 * the candidate row names the human authorises on a login-state draft card.
 * Carries no values — the server snapshot owns them, and core refuses any
 * name its snapshot does not carry.
 */
public record CheckedSubsetRequest(List<String> names) {
}
