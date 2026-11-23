package com.sixbean.testpike.admin.plans.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.plans.api.dto.CompletionCheckView;
import com.sixbean.testpike.admin.plans.api.dto.GenerateBatchResultView;
import com.sixbean.testpike.admin.plans.api.dto.PlanAssignRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanCaseView;
import com.sixbean.testpike.admin.plans.api.dto.PlanCreateRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanDeviationRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanDeviationView;
import com.sixbean.testpike.admin.plans.api.dto.PlanProgressView;
import com.sixbean.testpike.admin.plans.api.dto.PlanSummaryRow;
import com.sixbean.testpike.admin.plans.api.dto.PlanUpdateRequest;
import com.sixbean.testpike.admin.plans.api.dto.PlanView;
import com.sixbean.testpike.admin.plans.infrastructure.remote.client.CorePlanClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.api.dto.PageView;
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
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final CorePlanClient corePlanClient;
    private final ProjectAccessGuard guard;

    public PlanController(CorePlanClient corePlanClient, ProjectAccessGuard guard) {
        this.corePlanClient = corePlanClient;
        this.guard = guard;
    }

    /** Plan list (project members, H1 isolation, whitelisted server-side sorting). */
    @GetMapping
    public Response<PageView<PlanView>> list(@RequestParam long projectId,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size,
                                       @RequestParam(required = false) String sortBy,
                                       @RequestParam(required = false) String sortDir) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(corePlanClient.list(projectId, status, keyword, page, size, sortBy, sortDir));
    }

    
    @PostMapping
    public Response<PlanView> create(@RequestBody PlanCreateRequest request) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, request.projectId());
        return Response.builderSuccess(corePlanClient.create(request));
    }

    
    @GetMapping("/{id}")
    public Response<PlanView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanView(user, plan.projectId());
        return Response.builderSuccess(plan);
    }

    
    @GetMapping("/{id}/cases")
    public Response<List<PlanCaseView>> listCases(@PathVariable long id,
                                                  @RequestParam(required = false) Integer status) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanView(user, plan.projectId());
        return Response.builderSuccess(corePlanClient.listCases(id, status));
    }

    
    @PutMapping("/{id}")
    public Response<PlanView> update(@PathVariable long id, @RequestBody PlanUpdateRequest request) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanManage(user, plan.projectId());
        return Response.builderSuccess(corePlanClient.update(id, request));
    }

    
    @PostMapping("/{id}/assign")
    public Response<Integer> assign(@PathVariable long id, @RequestBody PlanAssignRequest request) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanManage(user, plan.projectId());
        return Response.builderSuccess(corePlanClient.assign(id, request));
    }

    
    @PostMapping("/{id}/publish")
    public Response<PlanView> publish(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanManage(user, plan.projectId());
        return Response.builderSuccess(corePlanClient.publish(id));
    }

    
    @PostMapping("/{id}/generate-batches")
    public Response<GenerateBatchResultView> generateBatches(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanManage(user, plan.projectId());
        return Response.builderSuccess(corePlanClient.generateBatches(id));
    }

    
    @RequirePermission("perm:testops:progress-board")
    @GetMapping("/{id}/progress")
    public Response<PlanProgressView> progress(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanView(user, plan.projectId());
        return Response.builderSuccess(corePlanClient.progress(id));
    }

 
    @RequirePermission("perm:testops:progress-board")
    @GetMapping("/progress-summary")
    public Response<List<PlanSummaryRow>> progressSummary(@RequestParam long projectId) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(corePlanClient.progressSummary(projectId));
    }

    
    @GetMapping("/{id}/completion-check")
    public Response<CompletionCheckView> completionCheck(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanManage(user, plan.projectId());
        return Response.builderSuccess(corePlanClient.completionCheck(id));
    }

    
    @PostMapping("/{id}/cancel")
    public Response<Void> cancel(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanManage(user, plan.projectId());
        corePlanClient.cancel(id);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id,
                                 @RequestParam(defaultValue = "false") boolean force) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanManage(user, plan.projectId());
        corePlanClient.delete(id, force);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/{id}/deviations")
    public Response<PlanDeviationView> registerDeviation(@PathVariable long id,
                                                         @RequestBody PlanDeviationRequest request) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanView(user, plan.projectId());
        return Response.builderSuccess(corePlanClient.registerDeviation(id, request));
    }

    
    @GetMapping("/{id}/deviations")
    public Response<List<PlanDeviationView>> listDeviations(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanView(user, plan.projectId());
        return Response.builderSuccess(corePlanClient.listDeviations(id));
    }

    
    @GetMapping("/{id}/case-allowed")
    public Response<Void> validateCaseAllowed(@PathVariable long id, @RequestParam long caseId) {
        CurrentUser user = UserContext.current();
        PlanView plan = corePlanClient.get(id);
        guard.assertCanView(user, plan.projectId());
        corePlanClient.validateCaseAllowed(id, caseId);
        return Response.builderSuccess(null);
    }
}
