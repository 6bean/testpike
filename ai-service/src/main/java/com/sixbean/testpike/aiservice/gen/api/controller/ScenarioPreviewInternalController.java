package com.sixbean.testpike.aiservice.gen.api.controller;

import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioEnumerationResult;
import com.sixbean.testpike.aiservice.gen.api.dto.ScenarioPreviewRequest;
import com.sixbean.testpike.aiservice.gen.domain.service.ScenarioEnumerationService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage0 scenario enumeration internal door .
 * <p>Consumer = the core preview ledger ONLY (core owns the ledger and the cost
 * accounting, this side owns the model-heavy ring — the MockLlmFallbackInternalController
 * posture: thin controller, single-point service). Path guard: the class-level
 * and method-level mappings together spell /internal/gen/scenario-preview,
 * mirrored character by character by RestAiServiceScenarioPreviewClient.
 * <p>When the K8 switch {@code ai.gen.scenario-expand.enabled} is off the
 * service returns the disabled shape (zero model calls) and nothing is
 * persisted upstream — this door never fabricates a scenario list.
 */
@RestController
@RequestMapping("/internal/gen")
public class ScenarioPreviewInternalController {

    private final ScenarioEnumerationService enumerationService;

    public ScenarioPreviewInternalController(ScenarioEnumerationService enumerationService) {
        this.enumerationService = enumerationService;
    }

    /** Enumerate one requirement line through the kernel deliberation ring. */
    @PostMapping("/scenario-preview")
    public Response<ScenarioEnumerationResult> preview(@RequestBody ScenarioPreviewRequest request) {
        return Response.builderSuccess(enumerationService.enumerate(
                request.requirementText(), request.priority(), request.projectId(),
                request.operatorId(), request.taskId()));
    }
}
