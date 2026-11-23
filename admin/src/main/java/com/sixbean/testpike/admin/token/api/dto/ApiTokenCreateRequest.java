package com.sixbean.testpike.admin.token.api.dto;

import java.time.LocalDateTime;

/**
 *
 * @param projectScope GA1 comma-separated numeric project ids to pin the token
 * to; null/blank = unrestricted (validation single source is core)
 */
public record ApiTokenCreateRequest(String name, String scopes, LocalDateTime expiresAt,
                                    String projectScope) {

    /** Pre-GA1 three-field shape: no project pin. */
    public ApiTokenCreateRequest(String name, String scopes, LocalDateTime expiresAt) {
        this(name, scopes, expiresAt, null);
    }
}
