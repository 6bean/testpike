package com.sixbean.testpike.core.token.api.dto;

import java.util.List;

/**
 *
 * @param tokenId presented token row id (only OK; per-token observability
 * attribution for the MCP ledger — GA3)
 * @param projectScope GA1 pinned project ids (parsed, empty = unrestricted);
 * the api MCP gateway enforces this pin without a re-read
 */
public record ApiTokenVerifyResult(
        boolean ok,
        ApiTokenVerifyStatus status,
        String message,
        Long userId,
        Long tokenId,
        List<String> scopes,
        List<String> projectScope) {

    /** Auth accepted with owner, token identity and scopes (pre-GA1 shape). */
    public static ApiTokenVerifyResult ok(Long tokenId, Long userId, List<String> scopes) {
        return new ApiTokenVerifyResult(true, ApiTokenVerifyStatus.OK, null, userId, tokenId,
                scopes, List.of());
    }

    /** Auth accepted including the GA1 project pin (empty list = unrestricted). */
    public static ApiTokenVerifyResult ok(Long tokenId, Long userId, List<String> scopes,
                                          List<String> projectScope) {
        return new ApiTokenVerifyResult(true, ApiTokenVerifyStatus.OK, null, userId, tokenId,
                scopes, projectScope == null ? List.of() : projectScope);
    }

    
    public static ApiTokenVerifyResult fail(ApiTokenVerifyStatus status, String message) {
        return new ApiTokenVerifyResult(false, status, message, null, null, List.of(), List.of());
    }
}
