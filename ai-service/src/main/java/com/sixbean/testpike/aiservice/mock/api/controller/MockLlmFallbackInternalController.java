package com.sixbean.testpike.aiservice.mock.api.controller;

import com.sixbean.testpike.aiservice.mock.api.dto.MockLlmFallbackGenerateRequest;
import com.sixbean.testpike.aiservice.mock.api.dto.MockLlmFallbackGenerateResult;
import com.sixbean.testpike.aiservice.mock.domain.service.MockLlmFallbackGenService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-3 LLM fallback sync generation internal door (ai-service; .
 * <p>Consumer = the core control plane ONLY (WxMockInternalController a/b
 * precedent: the external mock-server never opens a second outbound leg to this
 * service — it rides core's snapshot/store doors and core decides when a miss
 * may escalate here). The provider keys and the fallback chain live on this
 * side; gates, budget and cache stay single-sourced in core.
 * <p>Controller is thin by design: identity guard, prompt assembly and the
 * structural artifact validation are service-level single points. A provider
 * failure throws — core treats the throw as "no fallback", degrading the
 * traffic plane to its original miss semantics, never a 500 dressed up as an
 * answer.
 */
@RestController
@RequestMapping("/internal/mock/llm-fallback")
public class MockLlmFallbackInternalController {

    private final MockLlmFallbackGenService generationService;

    public MockLlmFallbackInternalController(MockLlmFallbackGenService generationService) {
        this.generationService = generationService;
    }

    /** Produce one fallback answer for a masked fingerprint and case it via core /store. */
    @PostMapping("/generate")
    public Response<MockLlmFallbackGenerateResult> generate(
            @RequestBody MockLlmFallbackGenerateRequest request) {
        return Response.builderSuccess(generationService.generate(request));
    }
}
