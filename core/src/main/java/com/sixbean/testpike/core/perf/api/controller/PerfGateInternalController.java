package com.sixbean.testpike.core.perf.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.perf.api.dto.PerfApprovalInput;
import com.sixbean.testpike.core.perf.api.dto.PerfGateCheckInput;
import com.sixbean.testpike.core.perf.api.dto.PerfWhitelistInput;
import com.sixbean.testpike.core.perf.domain.component.PerfAccessGate;
import com.sixbean.testpike.core.perf.domain.component.PerfGateDecision;
import com.sixbean.testpike.core.perf.domain.service.PerfApprovalService;
import com.sixbean.testpike.core.perf.domain.service.PerfWhitelistService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/perf")
public class PerfGateInternalController {

    private final PerfWhitelistService whitelistService;
    private final PerfApprovalService approvalService;
    private final PerfAccessGate accessGate;
    private final com.sixbean.testpike.core.perf.domain.service.PerfRunStopService perfRunStopService;
    private final com.sixbean.testpike.core.perf.domain.service.PerfReportNotifyService perfReportNotifyService;

    public PerfGateInternalController(PerfWhitelistService whitelistService,
                                      PerfApprovalService approvalService,
                                      PerfAccessGate accessGate,
                                      com.sixbean.testpike.core.perf.domain.service.PerfRunStopService
                                              perfRunStopService,
                                      com.sixbean.testpike.core.perf.domain.service.PerfReportNotifyService
                                              perfReportNotifyService) {
        this.whitelistService = whitelistService;
        this.approvalService = approvalService;
        this.accessGate = accessGate;
        this.perfRunStopService = perfRunStopService;
        this.perfReportNotifyService = perfReportNotifyService;
    }

    
    @PostMapping("/whitelist")
    public Response<Long> createWhitelist(@RequestParam long projectId,
                                          @RequestBody PerfWhitelistInput input) {
        return Response.builderSuccess(whitelistService.create(projectId, input));
    }

    
    @DeleteMapping("/whitelist/{id}")
    public Response<Void> deleteWhitelist(@PathVariable long id, @RequestParam long projectId) {
        whitelistService.delete(projectId, id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/whitelist")
    public Response<List<Map<String, Object>>> listWhitelist(@RequestParam long projectId) {
        return Response.builderSuccess(whitelistService.list(projectId));
    }

    
    @PostMapping("/approvals")
    public Response<Long> applyApproval(@RequestParam long projectId,
                                        @RequestBody PerfApprovalInput input) {
        Long operator = OperatorContext.currentOperatorId();
        return Response.builderSuccess(approvalService.apply(projectId,
                operator == null ? 0L : operator, input));
    }

    
    @PostMapping("/approvals/{id}/approve")
    public Response<Void> approve(@PathVariable long id) {
        Long operator = OperatorContext.currentOperatorId();
        approvalService.approve(id, operator == null ? 0L : operator);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/approvals/{id}/reject")
    public Response<Void> reject(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Long operator = OperatorContext.currentOperatorId();
        approvalService.reject(id, operator == null ? 0L : operator,
                (String) body.get("rejectReason"));
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/approvals")
    public Response<List<Map<String, Object>>> listApprovals(@RequestParam long projectId) {
        return Response.builderSuccess(approvalService.list(projectId));
    }

    
    @PostMapping("/gate/precheck")
    public Response<PerfGateDecision> precheck(@RequestBody PerfGateCheckInput input) {
        return Response.builderSuccess(accessGate.evaluate(input));
    }

    
    @PostMapping("/runs/{runId}/stop")
    public Response<Integer> stopRun(@PathVariable long runId, @RequestParam long projectId) {
        Long operator = OperatorContext.currentOperatorId();
        return Response.builderSuccess(perfRunStopService.stopRun(projectId, runId, operator));
    }

    
    @PostMapping("/runs/stop-all")
    public Response<Integer> stopAll(@RequestParam long projectId) {
        Long operator = OperatorContext.currentOperatorId();
        return Response.builderSuccess(perfRunStopService.stopAll(projectId, operator));
    }

    
    @PostMapping("/runs/{runId}/report-card")
    public Response<Integer> sendReportCard(@PathVariable long runId, @RequestParam long projectId) {
        return Response.builderSuccess(perfReportNotifyService.sendCard(projectId, runId));
    }
}
