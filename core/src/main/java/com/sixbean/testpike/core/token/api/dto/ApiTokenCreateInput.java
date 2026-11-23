package com.sixbean.testpike.core.token.api.dto;

import java.time.LocalDateTime;

/**
 *
 * @param name display alias shown in token lists
 * @param scopes comma-separated scope set, restricted to the mcp. prefix
 * @param expiresAt expiry instant; null means the token never expires
 * @param projectScope GA1 comma-separated numeric project ids the token is
 * pinned to; null/blank = unrestricted (the gateway applies
 * the owner-membership check only, same as pre-GA1 tokens)
 */
public record ApiTokenCreateInput(
        String name,
        String scopes,
        LocalDateTime expiresAt,
        String projectScope) {

    /** Pre-GA1 three-field shape: no project pin (unrestricted). */
    public ApiTokenCreateInput(String name, String scopes, LocalDateTime expiresAt) {
        this(name, scopes, expiresAt, null);
    }
}
