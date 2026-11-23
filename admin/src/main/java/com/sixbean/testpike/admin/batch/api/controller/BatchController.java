package com.sixbean.testpike.admin.batch.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.batch.api.dto.BatchCaseView;
import com.sixbean.testpike.admin.batch.api.dto.BatchCreateRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchFromTemplateRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchInfoView;
import com.sixbean.testpike.admin.batch.api.dto.BatchInsightView;
import com.sixbean.testpike.admin.batch.api.dto.BatchRecoveryView;
import com.sixbean.testpike.admin.batch.api.dto.BatchReportView;
import com.sixbean.testpike.admin.batch.api.dto.BatchStartResultView;
import com.sixbean.testpike.admin.batch.api.dto.BatchTemplateSaveRequest;
import com.sixbean.testpike.admin.batch.api.dto.BatchTemplateView;
import com.sixbean.testpike.admin.batch.api.dto.BatchUpdateRequest;
import com.sixbean.testpike.admin.batch.api.dto.TemplateScheduleRequest;
import com.sixbean.testpike.admin.batch.infrastructure.remote.client.CoreBatchClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
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
@RequestMapping("/api/v1/batches")
public class BatchController {

    private final CoreBatchClient coreBatchClient;
    private final ProjectAccessGuard guard;

    public BatchController(CoreBatchClient coreBatchClient, ProjectAccessGuard guard) {
        this.coreBatchClient = coreBatchClient;
        this.guard = guard;
    }

    
    @RequirePermission("perm:batch:batch-view")
    @GetMapping
    public Response<PageView<BatchInfoView>> list(@RequestParam long projectId,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String sortBy,
                                            @RequestParam(required = false) String sortDir,
                                            @RequestParam(defaultValue = "0") long programId) {
        CurrentUser user = UserContext.current();
        // Program lens : with no explicit project, core narrows the list to
        // lens projects ∩ the caller's visible domain (ProgramLensScope single source),
        // so the single-project guard is skipped — an unreachable lens yields an honest
        // empty page, never a leak and never 403 (same contract as the case domain).
        if (projectId != 0L || programId <= 0L) {
            guard.assertCanView(user, projectId);
        }
        return Response.builderSuccess(coreBatchClient.list(projectId, status, keyword, page, size,
                sortBy, sortDir, programId));
    }

    
    @RequirePermission("perm:batch:batch-view")
    @PostMapping
    public Response<BatchInfoView> create(@RequestBody BatchCreateRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteCase(user, request.projectId());
        return Response.builderSuccess(coreBatchClient.create(request));
    }

    
    @RequirePermission("perm:batch:batch-view")
    @GetMapping("/{id}")
    public Response<BatchInfoView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(batch);
    }

    /**
     * Batch execution process tail (R62 B-face): the owning project is
     * resolved server-side from the batch row and guarded before any ledger
     * row is forwarded, so a non-member never tails another project's batch
     * run. Paging replays the core cursor window (strictly increasing row
     * id), the same contract as the explore/generation tails.
     */
    @GetMapping("/{id}/process-events")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView>> processEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit) {
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(UserContext.current(), batch.projectId());
        return Response.builderSuccess(coreBatchClient.processEvents(id, cursor, limit));
    }

    
    @RequirePermission("perm:batch:batch-view")
    @PutMapping("/{id}")
    public Response<BatchInfoView> update(@PathVariable long id,
                                          @RequestBody BatchUpdateRequest request) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanWriteCase(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.update(id, request));
    }

    
    @RequirePermission("perm:batch:batch-view")
    @PostMapping("/{id}/start")
    public Response<BatchStartResultView> start(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanWriteCase(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.start(id));
    }

    
    @RequirePermission("perm:batch:batch-console")
    @PostMapping("/{id}/pause")
    public Response<BatchInfoView> pause(@PathVariable long id,
                                         @RequestParam(required = false) Integer version) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.pause(id, version));
    }

    
    @RequirePermission("perm:batch:batch-console")
    @PostMapping("/{id}/resume")
    public Response<BatchInfoView> resume(@PathVariable long id,
                                          @RequestParam(required = false) Integer version) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.resume(id, version));
    }

    
    @RequirePermission("perm:batch:batch-console")
    @PostMapping("/{id}/cancel")
    public Response<BatchInfoView> cancel(@PathVariable long id,
                                          @RequestParam(required = false) Integer version) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.cancel(id, version));
    }

    
    @RequirePermission("perm:batch:batch-console")
    @PostMapping("/{id}/recover")
    public Response<BatchRecoveryView> recover(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.recover(id));
    }

    
    @RequirePermission("perm:batch:batch-view")
    @GetMapping("/{id}/report")
    public Response<BatchReportView> report(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.report(id));
    }

    /** Failure-insight card read door (AO-7 stage 3): member gate mirrors the report
     *  door; data=null = honest absence (all-pass batch / gate off / clustering still
     *  pending) — the report page hides the card instead of erroring. */
    @RequirePermission("perm:batch:batch-view")
    @GetMapping("/{id}/insight")
    public Response<BatchInsightView> insight(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.insight(id));
    }

    
    @RequirePermission("perm:batch:batch-console")
    @PostMapping("/{id}/skip-case")
    public Response<String> skipCase(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.skipCase(id));
    }

    
    @RequirePermission("perm:batch:batch-console")
    @PostMapping("/{id}/rerun-failed")
    public Response<Integer> rerunFailed(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.rerunFailed(id));
    }

    
    @RequirePermission("perm:batch:batch-view")
    @GetMapping("/{id}/cases")
    public Response<List<BatchCaseView>> listCases(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanView(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.listCases(id));
    }

    
    @RequirePermission("perm:batch:batch-view")
    @PostMapping("/{id}/save-as-template")
    public Response<BatchTemplateView> saveAsTemplate(@PathVariable long id,
                                                      @RequestBody BatchTemplateSaveRequest request) {
        CurrentUser user = UserContext.current();
        BatchInfoView batch = coreBatchClient.get(id);
        guard.assertCanWriteCase(user, batch.projectId());
        return Response.builderSuccess(coreBatchClient.saveAsTemplate(id, request));
    }

    
    @RequirePermission("perm:batch:batch-view")
    @GetMapping("/templates")
    public Response<List<BatchTemplateView>> listTemplates(@RequestParam long projectId) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(coreBatchClient.listTemplates(projectId));
    }

    
    @RequirePermission("perm:batch:batch-view")
    @PostMapping("/from-template")
    public Response<BatchInfoView> createFromTemplate(@RequestBody BatchFromTemplateRequest request) {
        CurrentUser user = UserContext.current();
        BatchTemplateView tpl = coreBatchClient.getTemplate(request.templateId());
        guard.assertCanWriteCase(user, tpl.projectId());
        return Response.builderSuccess(coreBatchClient.createFromTemplate(request));
    }

    
    @DeleteMapping("/templates/{templateId}")
    public Response<Void> deleteTemplate(@PathVariable long templateId) {
        UserContext.current();
        coreBatchClient.deleteTemplate(templateId);
        return Response.builderSuccess(null);
    }

    
    @PutMapping("/templates/{templateId}/schedule")
    public Response<BatchTemplateView> updateTemplateSchedule(@PathVariable long templateId,
                                                              @RequestBody TemplateScheduleRequest request) {
        CurrentUser user = UserContext.current();
        BatchTemplateView tpl = coreBatchClient.getTemplate(templateId);
        guard.assertCanView(user, tpl.projectId());
        return Response.builderSuccess(coreBatchClient.updateTemplateSchedule(templateId, request));
    }
}
