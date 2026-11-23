package com.sixbean.testpike.aiservice.mock.api.dto;

import java.util.Map;

/**
 * A-3 sync generation result ; the ai-service door's answer to
 * the core orchestrator).
 * <p>{@code stored} relays core's honest cache verdict verbatim — a refused
 * store (over budget, gates closed mid-flight) still carries the answer that
 * was paid for, because the traffic plane must be able to reply to the
 * caller even when the bookkeeping leg declines. The refusal reason travels
 * in plain words, never as a bare flag.
 *
 * @param status HTTP status of the generated answer
 * @param headers response headers of the generated answer
 * @param body generated response body as text ("" allowed, null never)
 * @param tokensSpent real token cost of THIS generation (prompt+completion)
 * @param model model name that actually answered (fallback-chain truth)
 * @param stored whether core cased the artifact into the cache
 * @param storeReason plain words when nothing was stored (null when stored)
 */
public record MockLlmFallbackGenerateResult(
        int status,
        Map<String, String> headers,
        String body,
        int tokensSpent,
        String model,
        boolean stored,
        String storeReason) {
}
