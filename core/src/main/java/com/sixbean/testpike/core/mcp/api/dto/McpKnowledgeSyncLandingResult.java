package com.sixbean.testpike.core.mcp.api.dto;

/**
 * Payload landing counters : inserted = new ledger
 * version rows written, skipped = fingerprint-deduped replays (zero new rows,
 * idempotency assertion target).
 */
public record McpKnowledgeSyncLandingResult(
        int inserted,
        int skipped) {
}
