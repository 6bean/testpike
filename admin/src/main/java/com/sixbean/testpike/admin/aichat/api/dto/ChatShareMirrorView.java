package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Mirror of the core share row view ({@code ChatShareInfo}, AO batch
 * P2): what the owner sees in the share panel — the token (which is itself
 * the public credential of the frozen copy), the frozen title, the visible
 * turn count, when it dies and whether it was already revoked. Transport
 * only: every judgment (ownership, ttl clamp, anti-enumeration) stays in
 * core; admin never re-derives expiry or rewrites sentences.
 */
public record ChatShareMirrorView(
        long id,
        long sessionId,
        String shareToken,
        String title,
        int turnCount,
        java.time.LocalDateTime expiresAt,
        int revoked,
        java.time.LocalDateTime createdAt) {
}
