package com.sixbean.testpike.core.ai.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewInfo;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewInput;
import com.sixbean.testpike.core.ai.domain.service.ScenarioPreviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Stage0 scenario preview internal endpoints (core service layer, consumed by
 * the admin bridge; . Internal routes are exempt from the 02
 * registry per the established convention.
 * <p>POST runs one requirement line through the ai-service enumeration leg and
 * stores the surviving scenario list for the human circle-selection step;
 * GET reads a batch's previews back in submission order. The preview is a
 * draft ledger: it never writes case rows and never relaxes the three human
 * gates (selection / adoption / one-click cost confirmation).
 */
@RestController
@RequestMapping("/internal/ai-scenario-previews")
public class ScenarioPreviewInternalController {

    private final ScenarioPreviewService scenarioPreviewService;

    public ScenarioPreviewInternalController(ScenarioPreviewService scenarioPreviewService) {
        this.scenarioPreviewService = scenarioPreviewService;
    }

    /** Enumerate one line and persist its preview; returns the row id (0 = switch off). */
    @PostMapping
    public Response<Long> create(@RequestBody ScenarioPreviewInput input) {
        return Response.builderSuccess(scenarioPreviewService.create(input));
    }

    /** All previews of one batch in submission order (circle-selection read path). */
    @GetMapping
    public Response<List<ScenarioPreviewInfo>> listByBatch(@RequestParam long batchId) {
        return Response.builderSuccess(scenarioPreviewService.listByBatch(batchId));
    }
}
