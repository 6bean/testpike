package com.sixbean.testpike.core.mock.api.dto;

import java.util.Map;

/**
 * A-3 generated-answer cache write request .
 * <p>Carries the freshly paid generation back from the ai-service leg so the
 * same masked fingerprint is answered instantly next time. The snapshot shape
 * {@code {status,headers,body}} is enforced structurally here — the fields
 * arrive split, and the service assembles the object the read side accepts
 * (the isObject gate landed in writers and readers pair on one
 * shape, no second spectrum).
 *
 * @param spaceId serving space row id (cache is space-scoped)
 * @param method HTTP method as adjudicated (normalized to upper case)
 * @param path normalized endpoint path (template segments kept)
 * @param maskedFingerprint digest of the masked request (PII never enters the key)
 * @param status HTTP status of the generated answer (100..599)
 * @param headers response headers to store with the generation
 * @param body generated response body as text (never null; "" allowed)
 * @param tokensSpent token cost actually paid to produce this answer
 */
public record MockLlmFallbackStoreRequest(
        Long spaceId,
        String method,
        String path,
        String maskedFingerprint,
        int status,
        Map<String, String> headers,
        String body,
        int tokensSpent) {
}
