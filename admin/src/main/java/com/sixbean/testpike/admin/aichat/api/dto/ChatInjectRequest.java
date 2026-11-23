package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Body of the mid-turn steering door: the supplement the reader types while an
 * answer is still streaming. Identity never rides this body — the admin door
 * pins it from the login context, and the blank guard lives once on the core
 * side so the two layers never keep two copies of the same rule.
 *
 * @param content the supplement text to steer the running answer with
 */
public record ChatInjectRequest(String content) {
}
