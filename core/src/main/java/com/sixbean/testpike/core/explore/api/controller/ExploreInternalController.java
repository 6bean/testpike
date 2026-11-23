package com.sixbean.testpike.core.explore.api.controller;

import com.sixbean.testpike.common.clientobject.ExploreRunStatsUpdate;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.explore.api.dto.ExploreActInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreCancelResult;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreEstimate;
import com.sixbean.testpike.core.explore.api.dto.ExploreEvidenceGrantView;
import com.sixbean.testpike.core.explore.api.dto.ExploreStandaloneActInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.api.dto.ExploreStatusUpdate;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepView;
import com.sixbean.testpike.core.explore.api.dto.ExploreToCasesInput;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/explore")
public class ExploreInternalController {

    private final ExploreService exploreService;
    private final AiGenPublisher aiGenPublisher;
    /** Shared process-event window door (single source; no explore-specific reader). */
    private final com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader taskEvents;

    public ExploreInternalController(ExploreService exploreService,
                                     AiGenPublisher aiGenPublisher,
                                     com.sixbean.testpike.core.events.domain.component
                                             .TaskEventWindowReader taskEvents) {
        this.exploreService = exploreService;
        this.aiGenPublisher = aiGenPublisher;
        this.taskEvents = taskEvents;
    }

    
    @PostMapping("/sessions")
    public Response<ExploreSessionView> create(@RequestParam long projectId,
                                               @RequestBody ExploreCreateInput input) {
        long operatorId = OperatorContext.currentOperatorId() == null
                ? 0L : OperatorContext.currentOperatorId();
        long sessionId = exploreService.create(operatorId, projectId, input);
        aiGenPublisher.publishExploreRun(new ExploreRunMessage(sessionId, projectId, operatorId));
        return Response.builderSuccess(exploreService.get(sessionId));
    }

    
    @PostMapping("/sessions/estimate")
    public Response<ExploreEstimate> estimate(@RequestParam long projectId,
                                              @RequestBody ExploreCreateInput input) {
        return Response.builderSuccess(exploreService.estimate(projectId, input));
    }

    
    @GetMapping("/sessions/{id}")
    public Response<ExploreSessionView> get(@PathVariable long id) {
        return Response.builderSuccess(exploreService.get(id));
    }

    
    @GetMapping("/sessions")
    public Response<PageResult<ExploreSessionView>> list(@RequestParam long projectId,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String sortBy,
                                                          @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(exploreService.list(projectId, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/sessions/{id}/steps")
    public Response<List<ExploreStepView>> steps(@PathVariable long id) {
        return Response.builderSuccess(exploreService.steps(id));
    }

    
    @GetMapping("/sessions/{id}/steps/{seq}/screenshot")
    public Response<String> screenshotUrl(@PathVariable long id, @PathVariable int seq) {
        return Response.builderSuccess(exploreService.screenshotUrl(id, seq));
    }

    
    @GetMapping("/sessions/{id}/evidence-grant")
    public Response<ExploreEvidenceGrantView> grantEvidence(@PathVariable long id,
                                                            @RequestParam String kind,
                                                            @RequestParam String fileName) {
        return Response.builderSuccess(exploreService.grantEvidence(id, kind, fileName));
    }

    
    @PutMapping("/sessions/{id}/status")
    public Response<ExploreSessionView> updateStatus(@PathVariable long id,
                                                     @RequestBody ExploreStatusUpdate update) {
        return Response.builderSuccess(exploreService.updateStatus(id, update.status()));
    }

    
    @PutMapping("/sessions/{id}/stats")
    public Response<ExploreSessionView> updateStats(@PathVariable long id,
                                                    @RequestBody ExploreRunStatsUpdate stats) {
        return Response.builderSuccess(exploreService.updateStats(id, stats));
    }

    
    @PostMapping("/sessions/{id}/steps")
    public Response<Void> recordStep(@PathVariable long id, @RequestBody ExploreStepInput input) {
        exploreService.recordStep(id, input);
        return Response.builderSuccess();
    }

    
    @PostMapping("/sessions/{id}/acts")
    public Response<Long> submitAct(@PathVariable long id, @RequestBody ExploreActInput input) {
        return Response.builderSuccess(
                exploreService.submitAct(id, input == null ? null : input.actionJson()));
    }

    
    @PostMapping("/acts")
    public Response<Long> standaloneAct(@RequestBody ExploreStandaloneActInput input) {
        return Response.builderSuccess(exploreService.submitStandaloneAct(
                input == null || input.projectId() == null ? 0L : input.projectId(),
                input == null ? null : input.actionJson()));
    }

    /**
     * R62 A-face: cursor window of the process stream the exploration loop
     * already writes for this session (job kind {@code explore}, job_ref =
     * run_token = session id). The session row is read first so an unknown id
     * refuses with the honest not-found wording before the ledger is touched.
     * Replay follows the shared cursor contract (strictly increasing row id),
     * the same posture as the login-state probe tail: the row list is returned
     * as-is, no session-specific envelope, no second reader. Membership
     * guarding lives on the admin bridge, which resolves the project from the
     * session row it already loads.
     */
    @GetMapping("/sessions/{id}/process-events")
    public Response<List<com.sixbean.testpike.core.events.api.dto.TaskEventRow>> processEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        exploreService.get(id);
        return Response.builderSuccess(taskEvents.since(
                com.sixbean.testpike.core.events.domain.component
                        .AiTaskEventTrimSweeper.KIND_EXPLORE,
                String.valueOf(id), String.valueOf(id), cursor, limit));
    }

    
    @PostMapping("/sessions/{id}/to-cases")
    public Response<List<Long>> toCases(@PathVariable long id,
                                        @RequestBody ExploreToCasesInput input) {
        long operatorId = OperatorContext.currentOperatorId() == null
                ? 0L : OperatorContext.currentOperatorId();
        return Response.builderSuccess(exploreService.toCases(
                operatorId, id, input == null ? null : input.seqs()));
    }

    
    @DeleteMapping("/sessions/{id}")
    public Response<ExploreCancelResult> cancel(@PathVariable long id) {
        return Response.builderSuccess(exploreService.cancel(id));
    }
}
