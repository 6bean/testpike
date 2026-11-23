package com.sixbean.testpike.core.spec.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecChangeAnalysisInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecChangeHandleRequest;
import com.sixbean.testpike.core.spec.api.dto.SpecChangeReanalyzeRequest;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecChangeAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Contract change-analysis queue internal endpoints (core service layer, consumed by
 * the admin bridge). Permission gates (H1 project isolation + requirement-write face)
 * run on the admin side; the operator travels via X-Operator-Id so created_by /
 * handled_by traces close the loop. State-machine rules (first version rejected,
 * duplicate disposition rejected, batch all-or-nothing) live in core only — the
 * bridge must not fork them (DocChangeAnalysisInternalController shape precedent,
 * internal routes exempt from the 02 registry per established convention).
 */
@RestController
@RequestMapping("/internal/api-spec-change-analyses")
public class ApiSpecChangeAnalysisInternalController {

    private final ApiSpecChangeAnalysisService analysisService;

    public ApiSpecChangeAnalysisInternalController(ApiSpecChangeAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** Explicit re-analysis of one landed version (subscription poll / ops retry). */
    @PostMapping("/reanalyze")
    public Response<ApiSpecChangeAnalysisInfo> reanalyze(@RequestBody SpecChangeReanalyzeRequest req) {
        return Response.builderSuccess(analysisService.analyze(
                OperatorContext.currentOperatorId(), req.newSpecId(),
                req.detectedSource() == null ? "poll" : req.detectedSource()));
    }

    /** Review-queue page (projectId required; status optional; id DESC newest first). */
    @GetMapping
    public Response<PageResult<ApiSpecChangeAnalysisInfo>> list(@RequestParam long projectId,
                                                                @RequestParam(required = false) Integer status,
                                                                @RequestParam(defaultValue = "1") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(analysisService.list(projectId, status, page, size));
    }

    /** Single row by primary key (admin bridge reads it for the permission lookup). */
    @GetMapping("/{id}")
    public Response<ApiSpecChangeAnalysisInfo> get(@PathVariable long id) {
        return Response.builderSuccess(analysisService.getById(id));
    }

    /** Queue row of a contract version (by new_spec_id; none = 404). */
    @GetMapping("/by-spec/{newSpecId}")
    public Response<ApiSpecChangeAnalysisInfo> getBySpec(@PathVariable long newSpecId) {
        return Response.builderSuccess(analysisService.get(newSpecId));
    }

    /** Disposition trace (pending → handled, this row only; duplicate = 400). */
    @PostMapping("/{id}/handle")
    public Response<ApiSpecChangeAnalysisInfo> handle(@PathVariable long id,
                                                      @RequestBody(required = false) SpecChangeHandleRequest request) {
        return Response.builderSuccess(analysisService.handle(OperatorContext.currentOperatorId(),
                id, request == null ? null : request.note()));
    }

    /** Batch disposition of checked rows (all-or-nothing, unknown/handled rows named). */
    @PostMapping("/handle-batch")
    public Response<List<ApiSpecChangeAnalysisInfo>> handleBatch(@RequestBody SpecChangeHandleRequest request) {
        return Response.builderSuccess(analysisService.handleBatch(
                OperatorContext.currentOperatorId(), request.ids(), request.note()));
    }
}
