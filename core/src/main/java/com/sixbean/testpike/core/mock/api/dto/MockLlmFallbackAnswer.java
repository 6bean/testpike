package com.sixbean.testpike.core.mock.api.dto;

import java.util.Map;

/**
 * A-3 LLM fallback answer envelope .
 * <p>{@code served=false} is never dressed up: {@code missReason} carries the
 * plain-word explanation the traffic plane may surface in its 404 trace, and
 * a cache hit reports {@code cacheHit=true} with {@code tokensSpent=0} — the
 * honesty invariant of the whole cache table is that reads re-pay nothing.
 *
 * @param served whether an answer stands behind this envelope
 * @param cacheHit whether the answer came from the generated-answer cache
 * @param status HTTP status of the cached generation (0 when unserved)
 * @param headers response headers recorded with the generation
 * @param body generated response body as text (null when unserved)
 * @param tokensSpent token cost paid for THIS request (cache reads pay zero)
 * @param missReason plain words for why nothing was served (null when served)
 * @param cacheKey fingerprint key looked up (echoed so writers and readers
 * can prove they speak the same scheme)
 */
public record MockLlmFallbackAnswer(
        boolean served,
        boolean cacheHit,
        int status,
        Map<String, String> headers,
        String body,
        int tokensSpent,
        String missReason,
        String cacheKey) {

    /** Honest miss with an explanation — the only unserved shape allowed. */
    public static MockLlmFallbackAnswer miss(String cacheKey, String reason) {
        return new MockLlmFallbackAnswer(false, false, 0, Map.of(), null, 0, reason, cacheKey);
    }
}
