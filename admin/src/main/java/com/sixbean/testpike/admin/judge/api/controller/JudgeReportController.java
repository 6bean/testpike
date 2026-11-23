package com.sixbean.testpike.admin.judge.api.controller;

import com.sixbean.testpike.admin.judge.api.dto.GenerationSessionCardView;
import com.sixbean.testpike.admin.judge.api.dto.RunCostCardView;
import com.sixbean.testpike.admin.judge.api.dto.RunDiffView;
import com.sixbean.testpike.admin.judge.api.dto.RunSampleCardPageView;
import com.sixbean.testpike.admin.judge.api.dto.RunScorecardView;
import com.sixbean.testpike.admin.judge.api.dto.ScorecardTrendPointView;
import com.sixbean.testpike.admin.judge.infrastructure.remote.client.CoreJudgeClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>Permission doors migrated by the M1 ruling: all six read lanes ride the
 * dedicated key perm:judge:run:view (was the borrowed knowledge G7 key; the
 * crowd equivalence is pinned by JudgeDomainPermissionWiringTest).
 */
@RestController
@RequestMapping("/api/v1/judge")
public class JudgeReportController {

    private final CoreJudgeClient coreClient;

    public JudgeReportController(CoreJudgeClient coreClient) {
        this.coreClient = coreClient;
    }

 
    @RequirePermission("perm:judge:run:view")
    @GetMapping("/runs/{runId}/sample-cards")
    public Response<RunSampleCardPageView> sampleCards(@PathVariable long runId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return Response.builderSuccess(coreClient.sampleCards(runId, page, size));
    }

    
    @RequirePermission("perm:judge:run:view")
    @GetMapping("/runs/diff")
    public Response<RunDiffView> diffRuns(@RequestParam long a, @RequestParam long b,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(coreClient.diffRuns(a, b, page, size, sortBy, sortDir));
    }

    
    @RequirePermission("perm:judge:run:view")
    @GetMapping("/runs/{runId}/cost-card")
    public Response<RunCostCardView> costCard(@PathVariable long runId) {
        return Response.builderSuccess(coreClient.costCard(runId));
    }

    
    @RequirePermission("perm:judge:run:view")
    @GetMapping("/runs/{runId}/scorecard")
    public Response<RunScorecardView> scorecard(@PathVariable long runId) {
        return Response.builderSuccess(coreClient.scorecard(runId));
    }

    
    @RequirePermission("perm:judge:run:view")
    @GetMapping("/scorecard/trend")
    public Response<List<ScorecardTrendPointView>> scorecardTrend(
            @RequestParam String rubricName, @RequestParam String rubricDomain,
            @RequestParam(defaultValue = "50") int limit) {
        return Response.builderSuccess(coreClient.trend(rubricName, rubricDomain, limit));
    }

    
    @RequirePermission("perm:judge:run:view")
    @GetMapping("/scorecard/session-cards")
    public Response<List<GenerationSessionCardView>> scorecardSessionCards(
            @RequestParam String rubricName, @RequestParam String rubricDomain,
            @RequestParam(defaultValue = "50") int limit) {
        return Response.builderSuccess(coreClient.sessionCards(rubricName, rubricDomain, limit));
    }
}
