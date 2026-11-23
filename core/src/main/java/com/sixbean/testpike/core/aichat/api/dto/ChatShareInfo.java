package com.sixbean.testpike.core.aichat.api.dto;

/**
 * One dialog snapshot share row as its owner sees it .
 * {@code shareToken} is the non-enumerable UUID the public URL carries (CI
 * badge posture, V59): the link holder reads the frozen snapshot with this
 * token alone, so it is a credential and never appears in list pages meant
 * for other people. {@code expiresAt}/{@code revoked} tell the owner how long
 * the link lives and whether they already killed it.
 */
public record ChatShareInfo(
        long id,
        long sessionId,
        String shareToken,
        String title,
        int turnCount,
        java.time.LocalDateTime expiresAt,
        int revoked,
        java.time.LocalDateTime createdAt) {
}
