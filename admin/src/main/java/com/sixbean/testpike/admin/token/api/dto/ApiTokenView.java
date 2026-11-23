package com.sixbean.testpike.admin.token.api.dto;

/**
 *
 * @param projectScope GA1 pinned project ids ("3,12" form); null = unrestricted
 */
public record ApiTokenView(Long id, String name, String scopes, String fingerprint,
                           int revoked, String lastUsedAt, String expiresAt,
                           String projectScope) {

    /** Pre-GA1 seven-field shape (pin unknown to older callers = unrestricted). */
    public ApiTokenView(Long id, String name, String scopes, String fingerprint,
                        int revoked, String lastUsedAt, String expiresAt) {
        this(id, name, scopes, fingerprint, revoked, lastUsedAt, expiresAt, null);
    }
}
