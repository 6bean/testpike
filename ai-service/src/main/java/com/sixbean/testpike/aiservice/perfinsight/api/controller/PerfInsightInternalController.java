package com.sixbean.testpike.aiservice.perfinsight.api.controller;

import com.sixbean.testpike.aiservice.perfinsight.api.dto.PerfInsightRequest;
import com.sixbean.testpike.aiservice.perfinsight.domain.service.PerfInsightService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI load-test run-insight internal door (B-11 ; PerfDraftInternalController
 * family precedent: internal doors stay unlisted in the 02 public contract).
 * <p>Consumer = core orchestration leg (front-end/admin -> core -> this door;
 * architecture rule: the browser never calls ai-service directly, the run-scope
 * checks and facts assembly have their single point in core). Thin controller:
 * switch, parameters and parsing all live in the service layer.
 * <p>Path-join self-check: class prefix /internal/perf + method /insight =
 * /internal/perf/insight, exactly what the core RestClient calls.
 */
@RestController
@RequestMapping("/internal/perf")
public class PerfInsightInternalController {

    private final PerfInsightService perfInsightService;

    public PerfInsightInternalController(PerfInsightService perfInsightService) {
        this.perfInsightService = perfInsightService;
    }

    /** Attribution facts block -> transient {insight:"..."} read-out for the report card. */
    @PostMapping("/insight")
    public Response<Map<String, Object>> explain(@RequestBody PerfInsightRequest request) {
        return Response.builderSuccess(perfInsightService.explain(request));
    }
}
