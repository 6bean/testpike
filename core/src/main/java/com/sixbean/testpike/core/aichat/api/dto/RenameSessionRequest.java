package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Rename command of one dialog session . The user id rides the
 * body because the internal door is keyed by the session id alone — the admin
 * bridge pins it from the login context before calling; core re-checks
 * ownership, never trusting the wire (horizontal-access red line AF-g).
 */
public record RenameSessionRequest(long userId, String title) {
}
