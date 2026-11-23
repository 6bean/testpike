package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Create a dialog session through the internal session door (AO batch
 * γ). Field semantics and validation live in
 * {@code ChatSessionService.create} — the door forwards unchanged and never
 * keeps a second copy of the rules (context whitelist included).
 */
public record CreateSessionRequest(long projectId, long userId, String title,
                                   int source, String contextJson) {
}
