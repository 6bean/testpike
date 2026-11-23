package com.sixbean.testpike.core.mock.api.dto;

import java.util.Map;

/**
 * A-3 sync generation reply as the ai-service internal door answers the core
 * orchestrator . The HTTP shape of ai-service's
 * MockLlmFallbackGenerateResult. {@code stored} relays core's own cache
 * verdict (the generation leg cased its artifact through the store door on
 * the way out) — the orchestrator serves the paid answer regardless of the
 * bookkeeping verdict, because the caller already deserves what was spent.
 *
 * @param status HTTP status of the generated answer
 * @param headers response headers of the generated answer
 * @param body generated response body as text (never null by contract)
 * @param tokensSpent real token cost of THIS generation (prompt+completion)
 * @param model model name that actually answered
 * @param stored whether the artifact was cased into the cache
 * @param storeReason plain words when nothing was stored (null when stored)
 */
public record MockLlmFallbackGenerateReply(
        int status,
        Map<String, String> headers,
        String body,
        int tokensSpent,
        String model,
        boolean stored,
        String storeReason) {
}
