package com.sixbean.testpike.aiservice.mock.infrastructure.remote;

import java.util.Map;

/**
 * A-3 cache write payload for the core store door ; CoreGateway
 * remote brief pattern of the mock drafting family — the HTTP shape of core's
 * MockLlmFallbackStoreRequest, kept lean here instead of sharing api DTOs
 * across modules).
 *
 * @param spaceId serving space row id
 * @param method HTTP method as adjudicated
 * @param path normalized endpoint path
 * @param maskedFingerprint digest of the masked request (PII never travels)
 * @param status HTTP status of the generated answer
 * @param headers response headers recorded with the generation
 * @param body generated response body as text (never null)
 * @param tokensSpent token cost actually paid to produce this answer
 */
public record MockLlmFallbackStoreInput(
        Long spaceId,
        String method,
        String path,
        String maskedFingerprint,
        int status,
        Map<String, String> headers,
        String body,
        int tokensSpent) {
}
