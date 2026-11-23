package com.sixbean.testpike.core.mock.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackAnswer;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackRequest;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackStoreRequest;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackStoreResult;
import com.sixbean.testpike.core.mock.domain.service.MockLlmFallbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-3 LLM fallback internal door (core control plane; .
 * <p>Consumer = the mock-server miss branch (the only outbound leg shape the
 * data plane is allowed: RuleLoader→core snapshot precedent). Gate refusals
 * throw here as business-coded errors — the traffic plane degrades a refusal
 * to its original miss semantics, never a 500 dressed up as a fallback.
 * <p>Controller is thin by design: dual-gate ANDing, budget gate and cache
 * freshness are single points inside the service, never re-checked across
 * doors (red line 6: no second spectrum).
 */
@RestController
@RequestMapping("/internal/mock/llm-fallback")
public class MockLlmFallbackInternalController {

    private final MockLlmFallbackService fallbackService;

    public MockLlmFallbackInternalController(MockLlmFallbackService fallbackService) {
        this.fallbackService = fallbackService;
    }

    /** Cache-side answer for one masked fingerprint (served row or honest miss). */
    @PostMapping("/answer")
    public Response<MockLlmFallbackAnswer> answer(@RequestBody MockLlmFallbackRequest request) {
        return Response.builderSuccess(fallbackService.answer(request));
    }

    /**
 * Case one freshly paid generation into the cache write door,
 * consumed by the ai-service sync generation leg after it spends tokens).
 * Gate refusals and malformed payloads throw as business-coded errors;
 * an over-budget generation comes back as stored=false in plain words —
 * the generation leg already answered its caller, so the cost ceiling
 * never turns into a 500 on the bookkeeping leg.
 */
    @PostMapping("/store")
    public Response<MockLlmFallbackStoreResult> store(
            @RequestBody MockLlmFallbackStoreRequest request) {
        return Response.builderSuccess(fallbackService.store(request));
    }
}
