package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Wire body of the mid-turn steering door: the logged-in user id rides from
 * the admin login context (same posture as the feedback door), the content is
 * the user's supplement text. Ownership is re-checked server-side against the
 * session - the door never trusts the caller's claim alone.
 *
 * @param userId  the logged-in user submitting the injection
 * @param content the supplement text to land on the running turn
 */
public record InjectUserRequest(long userId, String content) {
}
