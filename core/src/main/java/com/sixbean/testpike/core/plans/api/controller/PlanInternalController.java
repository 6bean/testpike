package com.sixbean.testpike.core.plans.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.plans.api.dto.CompletionCheckView;
import com.sixbean.testpike.core.plans.api.dto.GenerateBatchResult;
import com.sixbean.testpike.core.plans.api.dto.PlanAssignInput;
import com.sixbean.testpike.core.plans.api.dto.PlanCaseInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanDeviationInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanDeviationInput;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.api.dto.PlanProgressView;
import com.sixbean.testpike.core.plans.api.dto.PlanQuery;
import com.sixbean.testpike.core.plans.api.dto.PlanSummaryRow;
import com.sixbean.testpike.core.plans.api.dto.PlanUpdateInput;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
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
@RequestMapping("/internal/plans")
public class PlanInternalController {

    private final PlanService planService;

    public PlanInternalController(PlanService planService) {
        this.planService = planService;
    }

    
    @PostMapping
    public Response<PlanInfo> create(@RequestBody PlanCreateInput input) {
        return Response.builderSuccess(planService.create(OperatorContext.currentOperatorId(), input));
    }

 
    @PutMapping("/{id}")
    public Response<PlanInfo> update(@PathVariable long id, @RequestBody PlanUpdateInput input) {
        return Response.builderSuccess(planService.update(OperatorContext.currentOperatorId(), id, input));
    }

    
    @GetMapping("/{id}")
    public Response<PlanInfo> get(@PathVariable long id) {
        return Response.builderSuccess(planService.get(id));
    }

    /** Plan list (H1 project isolation, status filter, name search, paging and whitelisted sorting). */
    @GetMapping
    public Response<PageResult<PlanInfo>> list(@RequestParam long projectId,
                                               @RequestParam(required = false) Integer status,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String sortBy,
                                               @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(planService.list(new PlanQuery(projectId, status, keyword, page, size, sortBy, sortDir)));
    }

    
    @GetMapping("/{id}/cases")
    public Response<List<PlanCaseInfo>> listCases(@PathVariable long id,
                                                  @RequestParam(required = false) Integer status) {
        return Response.builderSuccess(planService.listCases(id, status));
    }

    
    @PostMapping("/{id}/assign")
    public Response<Integer> assign(@PathVariable long id, @RequestBody PlanAssignInput input) {
        return Response.builderSuccess(planService.assignCases(OperatorContext.currentOperatorId(), id, input));
    }

    
    @PostMapping("/{id}/publish")
    public Response<PlanInfo> publish(@PathVariable long id) {
        return Response.builderSuccess(planService.publish(OperatorContext.currentOperatorId(), id));
    }

    
    @PostMapping("/{id}/generate-batches")
    public Response<GenerateBatchResult> generateBatches(@PathVariable long id) {
        return Response.builderSuccess(
                planService.generateBatches(OperatorContext.currentOperatorId(), id));
    }

    
    @GetMapping("/{id}/progress")
    public Response<PlanProgressView> progress(@PathVariable long id) {
        return Response.builderSuccess(planService.progress(id));
    }

 
    @GetMapping("/progress-summary")
    public Response<List<PlanSummaryRow>> progressSummary(@RequestParam long projectId) {
        return Response.builderSuccess(planService.progressSummary(projectId));
    }

    
    @GetMapping("/{id}/completion-check")
    public Response<CompletionCheckView> completionCheck(@PathVariable long id) {
        return Response.builderSuccess(
                planService.completionCheck(OperatorContext.currentOperatorId(), id));
    }

 
    @PostMapping("/{id}/cancel")
    public Response<Void> cancel(@PathVariable long id) {
        planService.cancel(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id,
                                 @RequestParam(defaultValue = "false") boolean force) {
        planService.delete(OperatorContext.currentOperatorId(), id, force);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/{id}/deviations")
    public Response<PlanDeviationInfo> registerDeviation(@PathVariable long id,
                                                         @RequestBody PlanDeviationInput input) {
        return Response.builderSuccess(planService.registerDeviation(
                OperatorContext.currentOperatorId(), id, input.caseId(),
                input.deviationType(), input.reason()));
    }

    
    @GetMapping("/{id}/deviations")
    public Response<List<PlanDeviationInfo>> listDeviations(@PathVariable long id) {
        return Response.builderSuccess(planService.listDeviations(id));
    }

    
    @GetMapping("/{id}/case-allowed")
    public Response<Void> validateCaseAllowed(@PathVariable long id, @RequestParam long caseId) {
        planService.validateCaseAllowed(id, caseId);
        return Response.builderSuccess(null);
    }
}
