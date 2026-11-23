package com.sixbean.testpike.admin.token.api.dto;

/**
 * GA3 per-token usage roll-up surfaced to the token management page (S2b-admin
 * bridge). Field names mirror core's McpTokenUsageView one-to-one so the REST
 * payload deserializes by name with zero manual mapping — ownership and the
 * admin read-through stay decided once inside core (bridge adds no checks).
 *
 * @param tokenId       the API token the numbers belong to
 * @param calls         total invocation rows attributed to the token
 * @param failures      rows with success = 0
 * @param p95DurationMs 95th percentile of non-null durations in milliseconds;
 *                      null when the token has no latency sample yet (passed
 *                      through honestly, never faked as 0)
 */
public record ApiTokenUsageView(
        long tokenId,
        long calls,
        long failures,
        Double p95DurationMs) {
}
