package com.sixbean.testpike.core.token.api.dto;

/**
 *
 * @param projectScope GA1 pinned project ids (normalized "3,12" form);
 * null/empty = unrestricted
 */
public record ApiTokenView(
        Long id,
        String name,
        String scopes,
        String fingerprint,
        int revoked,
        String lastUsedAt,
        String expiresAt,
        String projectScope) {
}
