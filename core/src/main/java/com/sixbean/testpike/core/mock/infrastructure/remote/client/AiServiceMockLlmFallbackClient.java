package com.sixbean.testpike.core.mock.infrastructure.remote.client;

import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackGenerateInput;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackGenerateReply;

/**
 * Outbound leg of the core A-3 orchestrator toward the ai-service sync
 * generation door . The interface exists so the orchestrator
 * stays testable without a live provider (L2 isolates this infrastructure
 * leg only — the traffic chain itself is verified live at L3/L4, red line 3).
 * A thrown RuntimeException means "no fallback answer" — the caller degrades
 * to the original miss semantics, never a 500 dressed up as a fallback.
 */
public interface AiServiceMockLlmFallbackClient {

    /** Produce one paid fallback answer, or fail (caller treats any throw as miss). */
    MockLlmFallbackGenerateReply generate(MockLlmFallbackGenerateInput input);
}
