package com.sixbean.testpike.mockserver.domain.component;

import java.util.Map;

/**
 * What the core answer door said about one miss .
 * <p>{@code served=false} is the ONLY failure shape the traffic plane may
 * see: gate refusals, cold misses, and outbound trouble all decode here,
 * each with the plain-word reason core surfaced (or named locally). The
 * caller falls through to its original 404+trace semantics in every
 * unserved case — a broken fallback never becomes a dressed-up answer.
 *
 * @param served whether a fallback answer stands
 * @param status HTTP status of the generated answer (0 when unserved)
 * @param headers response headers recorded with the generation
 * @param body generated body as text (null when unserved)
 * @param cacheHit whether core answered from the generated-answer cache
 * @param tokensSpent token cost of THIS request (cache reads pay zero)
 * @param missReason plain words when unserved (null when served)
 */
public record LlmFallbackOutcome(
        boolean served,
        int status,
        Map<String, String> headers,
        String body,
        boolean cacheHit,
        int tokensSpent,
        String missReason) {

    /** The one unserved shape a caller may fall through on. */
    public static LlmFallbackOutcome miss(String reason) {
        return new LlmFallbackOutcome(false, 0, Map.of(), null, false, 0, reason);
    }
}
