package com.sixbean.testpike.aiservice.perfdraft.api.controller;

import com.sixbean.testpike.aiservice.perfdraft.api.dto.PerfDraftRequest;
import com.sixbean.testpike.aiservice.perfdraft.domain.service.PerfDraftService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI load-test scenario drafting internal door (B-11; NlStepInternalController
 * family precedent: internal doors stay unlisted in the 02 public contract).
 * <p>Consumer = core orchestration leg (front-end/admin -> core -> this door;
 * architecture rule: the browser never calls ai-service directly, trimming and
 * re-validation have their single point in core). Thin controller: switch,
 * parameters and parsing all live in the service layer.
 * <p>Path-join self-check: class prefix /internal/perf + method /draft =
 * /internal/perf/draft, exactly what the core RestClient calls.
 */
@RestController
@RequestMapping("/internal/perf")
public class PerfDraftInternalController {

    private final PerfDraftService perfDraftService;

    public PerfDraftInternalController(PerfDraftService perfDraftService) {
        this.perfDraftService = perfDraftService;
    }

    /** One sentence -> draft {steps:[...]} returned verbatim for the wizard prefill. */
    @PostMapping("/draft")
    public Response<Map<String, Object>> draft(@RequestBody PerfDraftRequest request) {
        return Response.builderSuccess(perfDraftService.draft(request));
    }
}
