package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;

import java.time.Duration;

/**
 * One LLM call shape against a routed endpoint (AO batch dialog kernel,
 * . {@link LlmFallbackChain} owns the route/retry/degrade loop and
 * stays agnostic of how the per-endpoint call is made; non-streaming callers
 * bind it to {@code factory::chatFor}, streaming callers to
 * {@code factory::chatStreamFor}. Extracted so the streaming path reuses the
 * exact fallback semantics instead of copying the loop (single-source rule).
 */
public interface LlmEndpointCall {

    /** Call with the factory's configured default timeout (mock pass-through path). */
    LlmChatResult defaultTimeout(ChatCompletionRequest request, LlmEndpointConfig endpoint);

    /** Call with the chain-resolved attempt timeout (per-endpoint degrade path). */
    LlmChatResult withTimeout(ChatCompletionRequest request, LlmEndpointConfig endpoint,
                              Duration timeout);
}
