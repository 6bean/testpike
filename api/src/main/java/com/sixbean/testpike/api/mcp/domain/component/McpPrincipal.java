package com.sixbean.testpike.api.mcp.domain.component;

import java.util.List;

/**
 *
 * @param tokenId presenting token row id (per-token ledger attribution; null
 * only for legacy fixture-built principals)
 * @param projectScope GA1 pinned project ids (empty = unrestricted; the
 * gateway refuses off-scope targets with a 403 that
 * names the token's current project set)
 */
public record McpPrincipal(long userId, Long tokenId, List<String> scopes,
                          List<String> projectScope) {

    /** Shape without the GA1 pin (unrestricted token, pre-GA1 behavior). */
    public McpPrincipal(long userId, Long tokenId, List<String> scopes) {
        this(userId, tokenId, scopes, List.of());
    }

    /** Legacy two-field shape (fixtures predating per-token attribution). */
    public McpPrincipal(long userId, List<String> scopes) {
        this(userId, null, scopes, List.of());
    }
}
