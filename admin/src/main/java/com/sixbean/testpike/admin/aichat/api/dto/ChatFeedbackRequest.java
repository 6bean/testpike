package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Per-turn thumbs request on the user door: {@code feedback} 1 👍 / -1 👎 /
 * 0 toggled off, with the optional one-sentence {@code reason} that rides a
 * 👎. The owner id never appears here — the login context pins it, same as
 * every other dialog door (a body field cannot speak for another user).
 */
public record ChatFeedbackRequest(int feedback, String reason) {
}
