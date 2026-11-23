package com.sixbean.testpike.aiservice.mock.domain.service;

import com.sixbean.testpike.aiservice.mock.api.dto.MockLlmFallbackGenerateRequest;
import com.sixbean.testpike.aiservice.mock.api.dto.MockLlmFallbackGenerateResult;

/**
 * A-3 LLM fallback sync generation service .
 * <p>ai-service owns the provider keys; the core control plane owns the gates,
 * the budget and the cache. This door is the single generation leg: core
 * decides WHEN a miss may escalate (dual gates + token ceiling), ai-service
 * decides HOW the answer is produced (project-routed endpoint + fallback
 * chain), and the freshly paid artifact goes back through core's store door
 * so a repeated fingerprint answers instantly without a second spend.
 */
public interface MockLlmFallbackGenService {

    /** Generate one answer for a masked fingerprint and case it via core /store. */
    MockLlmFallbackGenerateResult generate(MockLlmFallbackGenerateRequest request);
}
