package com.sixbean.testpike.core.aichat.api.dto;

import java.util.List;

/**
 * Body of the checked-subset door (PATCH /internal/ai-chat/drafts/{id}/
 * checked-subset, piece D3): the candidate names the human ticks on the
 * card. Carries no values — the landing chain reloads them from the transient
 * capture slot at confirm time, and the door refuses any name the server-side
 * snapshot does not carry.
 */
public record CheckedSubsetRequest(List<String> names) {
}
