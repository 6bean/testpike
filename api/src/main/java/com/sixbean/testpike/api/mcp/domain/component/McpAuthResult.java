package com.sixbean.testpike.api.mcp.domain.component;


public record McpAuthResult(
        int httpStatus,
        boolean authenticated,
        String message,
        McpPrincipal principal) {

    static McpAuthResult reject(String message) {
        return new McpAuthResult(401, false, message, null);
    }

    static McpAuthResult accept(McpPrincipal principal) {
        return new McpAuthResult(200, true, null, principal);
    }
}
