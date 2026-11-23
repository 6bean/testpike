package com.sixbean.testpike.core.batch.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.batch.api.dto.BatchCaseInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchFromTemplateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchInsightUpsertInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInsightView;
import com.sixbean.testpike.core.batch.api.dto.BatchQuery;
import com.sixbean.testpike.core.batch.api.dto.BatchRecoveryView;
import com.sixbean.testpike.core.batch.api.dto.BatchReportView;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.batch.api.dto.BatchTemplateInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchUpdateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchStartInput;
import com.sixbean.testpike.core.batch.api.dto.BatchTemplateSaveRequest;
import com.sixbean.testpike.core.batch.api.dto.BadgeView;
import com.sixbean.testpike.core.batch.api.dto.CiVerdictView;
import com.sixbean.testpike.core.batch.api.dto.EnvOverrideInput;
import com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.batch.domain.service.BatchInsightService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
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
@RequestMapping("/internal/batches")
public class BatchInternalController {

    private final BatchService batchService;
    private final BatchInsightService batchInsightService;
    /** R62 B-face: shared cursor window reader for the batch-exec tail door. */
    private final com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader taskEvents;

    public BatchInternalController(BatchService batchService,
                                   BatchInsightService batchInsightService,
                                   com.sixbean.testpike.core.events.domain.component
                                           .TaskEventWindowReader taskEvents) {
        this.batchService = batchService;
        this.batchInsightService = batchInsightService;
        this.taskEvents = taskEvents;
    }

    /**
     * R62 B-face: cursor window over the process-event ledger of one batch
     * execution (job kind {@code batch-exec}, job_ref = run_token = batch id).
     * The pipeline itself records the frames - kickoff on start, dispatch on
     * claim, case terminal on result callback, sealed on finalize - so the
     * batch detail tail can replay progress without any manual refresh. The
     * batch row is read first so an unknown id refuses with the honest
     * not-found wording before the ledger is touched; membership guarding
     * lives on the admin bridge (same posture as the explore tail door).
     */
    @GetMapping("/{id}/process-events")
    public Response<List<com.sixbean.testpike.core.events.api.dto.TaskEventRow>> processEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        batchService.get(id);
        return Response.builderSuccess(taskEvents.since(
                com.sixbean.testpike.core.events.domain.component
                        .AiTaskEventTrimSweeper.KIND_BATCH_EXEC,
                String.valueOf(id), String.valueOf(id), cursor, limit));
    }

    /**
     * R67 revisit door for the batch tail: the aggregated run history of one
     * batch (newest run first), read through the shared run-history door —
     * the same shape the explore session and login-state probe doors answer,
     * no batch-specific aggregation (R40). Batch frames land with
     * job_ref = run_token = batch id, so the door answers per-run rows for
     * that anchor. The batch row is read first so an unknown id refuses with
     * the honest not-found wording before the ledger is touched; membership
     * guarding stays on the admin bridge. A blank limit passes through so the
     * door applies its own hot default window.
     */
    @GetMapping("/{id}/process-runs")
    public Response<List<com.sixbean.testpike.core.events.api.dto.TaskEventRunRow>> processRuns(
            @PathVariable long id, @RequestParam(required = false) Integer limit) {
        batchService.get(id);
        return Response.builderSuccess(taskEvents.runs(
                com.sixbean.testpike.core.events.domain.component
                        .AiTaskEventTrimSweeper.KIND_BATCH_EXEC,
                String.valueOf(id), limit));
    }

    
    @PostMapping
    public Response<BatchInfo> create(@RequestBody BatchCreateInput input) {
        return Response.builderSuccess(batchService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/{id}")
    public Response<BatchInfo> update(@PathVariable long id, @RequestBody BatchUpdateInput input) {
        if (input.version() == null) {
            return Response.builderSuccess(
                    batchService.update(OperatorContext.currentOperatorId(), id, input));
        }
        return Response.builderSuccess(batchService.update(
                OperatorContext.currentOperatorId(), id, input, input.version()));
    }

    
    @PostMapping("/{id}/start")
    public Response<BatchStartResult> start(@PathVariable long id,
                                            @RequestBody(required = false) BatchStartInput body) {
        long operator = OperatorContext.currentOperatorId();
        EnvOverrideInput overrides = body == null ? null : body.envOverrides();
        return Response.builderSuccess(overrides == null || !overrides.hasAny()
                ? batchService.start(operator, id)
                : batchService.start(operator, id, overrides));
    }

    
    @PostMapping("/{id}/pause")
    public Response<BatchInfo> pause(@PathVariable long id,
                                     @RequestParam(required = false) Integer version) {
        long operator = OperatorContext.currentOperatorId();
        return Response.builderSuccess(version == null
                ? batchService.pause(operator, id)
                : batchService.pause(operator, id, version));
    }

    
    @PostMapping("/{id}/resume")
    public Response<BatchInfo> resume(@PathVariable long id,
                                      @RequestParam(required = false) Integer version) {
        long operator = OperatorContext.currentOperatorId();
        return Response.builderSuccess(version == null
                ? batchService.resume(operator, id)
                : batchService.resume(operator, id, version));
    }

    
    @PostMapping("/{id}/cancel")
    public Response<BatchInfo> cancel(@PathVariable long id,
                                      @RequestParam(required = false) Integer version) {
        long operator = OperatorContext.currentOperatorId();
        return Response.builderSuccess(version == null
                ? batchService.cancel(operator, id)
                : batchService.cancel(operator, id, version));
    }

    
    @PostMapping("/{id}/recover")
    public Response<BatchRecoveryView> recover(@PathVariable long id) {
        return Response.builderSuccess(
                batchService.recover(OperatorContext.currentOperatorId(), id));
    }

    
    @PostMapping("/{id}/skip-case")
    public Response<String> skipCase(@PathVariable long id) {
        return Response.builderSuccess(batchService.skipCase(OperatorContext.currentOperatorId(), id));
    }

    
    @PostMapping("/{id}/rerun-failed")
    public Response<Integer> rerunFailed(@PathVariable long id) {
        return Response.builderSuccess(batchService.rerunFailed(OperatorContext.currentOperatorId(), id));
    }

    
    @GetMapping("/{id}")
    public Response<BatchInfo> get(@PathVariable long id) {
        return Response.builderSuccess(batchService.get(id));
    }

    
    @GetMapping("/badge/{badgeToken}")
    public Response<BadgeView> badge(@PathVariable String badgeToken) {
        return Response.builderSuccess(batchService.getByBadgeToken(badgeToken));
    }

    
    @GetMapping("/{id}/badge")
    public Response<BadgeView> badgeByBatchId(@PathVariable long id) {
        return Response.builderSuccess(batchService.badgeByBatchId(id));
    }

    
    @GetMapping("/{id}/verdict")
    public Response<CiVerdictView> verdict(@PathVariable long id) {
        return Response.builderSuccess(batchService.verdict(id));
    }

    
    @GetMapping("/{id}/report")
    public Response<BatchReportView> report(@PathVariable long id) {
        return Response.builderSuccess(batchService.report(id));
    }

    /**
     * Failure-insight landing write-back (AO-7 proactive insights, stage 2).
     * Consumed by the ai-service clustering consumer after a batch-finalized
     * event — ai-service never writes business tables directly (two-sides
     * separation). Validation (batch must exist) and the project-id derivation
     * are single-point inside the core service.
     */
    @PostMapping("/{id}/insight")
    public Response<Void> upsertInsight(@PathVariable long id,
                                        @RequestBody BatchInsightUpsertInput input) {
        batchInsightService.upsert(id, input);
        return Response.builderSuccess(null);
    }

    /**
     * Failure-insight read door (AO-7 stage 3) — consumed by the admin bridge for
     * the report-page insight card. Honest absence: data=null when nothing has
     * landed (all-pass batch, gate off, or clustering pending) — the card hides
     * itself instead of surfacing an error.
     */
    @GetMapping("/{id}/insight")
    public Response<BatchInsightView> insight(@PathVariable long id) {
        return Response.builderSuccess(batchInsightService.findByBatch(id));
    }

    /** Batch list (H1 project isolation, filters, paging and whitelisted sorting; temp batches stay out of the default view; optional program lens). */
    @GetMapping
    public Response<PageResult<BatchInfo>> list(@RequestParam long projectId,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(required = false) String sortBy,
                                                @RequestParam(required = false) String sortDir,
                                                @RequestParam(defaultValue = "0") long programId) {
        return Response.builderSuccess(
                batchService.list(new BatchQuery(projectId, status, keyword, page, size,
                        sortBy, sortDir, programId), OperatorContext.currentOperatorId()));
    }

    
    @GetMapping("/{id}/cases")
    public Response<List<BatchCaseInfo>> listCases(@PathVariable long id) {
        return Response.builderSuccess(batchService.listCases(id));
    }

    
    @PostMapping("/{id}/save-as-template")
    public Response<BatchTemplateInfo> saveAsTemplate(@PathVariable long id,
                                                      @RequestBody BatchTemplateSaveRequest request) {
        return Response.builderSuccess(batchService.saveAsTemplate(
                OperatorContext.currentOperatorId(), id, request.templateName()));
    }

    
    @GetMapping("/templates")
    public Response<List<BatchTemplateInfo>> listTemplates(@RequestParam long projectId) {
        return Response.builderSuccess(batchService.listTemplates(projectId));
    }

    
    @GetMapping("/templates/{templateId}")
    public Response<BatchTemplateInfo> getTemplate(@PathVariable long templateId) {
        return Response.builderSuccess(batchService.getTemplate(templateId));
    }

    
    @PostMapping("/from-template")
    public Response<BatchInfo> createFromTemplate(@RequestBody BatchFromTemplateInput input) {
        return Response.builderSuccess(batchService.createFromTemplate(
                OperatorContext.currentOperatorId(), input));
    }

    
    @DeleteMapping("/templates/{templateId}")
    public Response<Void> deleteTemplate(@PathVariable long templateId) {
        batchService.deleteTemplate(OperatorContext.currentOperatorId(), templateId);
        return Response.builderSuccess(null);
    }

    
    @PutMapping("/templates/{templateId}/schedule")
    public Response<BatchTemplateInfo> updateTemplateSchedule(@PathVariable long templateId,
                                                              @RequestBody TemplateScheduleInput input) {
        return Response.builderSuccess(batchService.updateTemplateSchedule(
                OperatorContext.currentOperatorId(), templateId, input));
    }
}
