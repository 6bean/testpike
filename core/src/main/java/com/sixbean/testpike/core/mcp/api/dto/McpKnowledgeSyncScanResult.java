package com.sixbean.testpike.core.mcp.api.dto;

/**
 * Due-scan round counters of the MCP knowledge sync scheduler ( AO-22
 * package 2, same shape as the spec subscription scan precedent).
 */
public record McpKnowledgeSyncScanResult(
        int scanned,
        int updated,
        int unchanged,
        int failed) {
}
