package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Wire shape of the per-turn feedback door: the owner id rides the body from
 * the login-pinned admin context (same posture as the rename door) — core
 * re-checks ownership and never trusts the wire identity alone. {@code reason}
 * is the optional one sentence captured with a 👎.
 */
public record RecordFeedbackRequest(long userId, int feedback, String reason) {
}
