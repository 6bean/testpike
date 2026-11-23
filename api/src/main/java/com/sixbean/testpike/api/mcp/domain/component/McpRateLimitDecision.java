package com.sixbean.testpike.api.mcp.domain.component;


public record McpRateLimitDecision(boolean allowed, String message, int retryAfterSeconds) {

    static McpRateLimitDecision allow() {
        return new McpRateLimitDecision(true, null, 0);
    }

    static McpRateLimitDecision deny(String message, int retryAfterSeconds) {
        return new McpRateLimitDecision(false, message, retryAfterSeconds);
    }
}
