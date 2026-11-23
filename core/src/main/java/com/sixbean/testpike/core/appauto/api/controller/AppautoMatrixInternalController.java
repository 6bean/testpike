package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixDispatchResult;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixFanoutInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixModelRow;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixPrecheckView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoMatrixService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoMatrixInternalController {

    private final AppautoMatrixService matrixService;

    public AppautoMatrixInternalController(AppautoMatrixService matrixService) {
        this.matrixService = matrixService;
    }

    
    @PostMapping("/matrix-precheck")
    public Response<AppautoMatrixPrecheckView> matrixPrecheck(
            @RequestBody AppautoMatrixFanoutInput input) {
        return Response.builderSuccess(matrixService.precheck(input));
    }

    
    @PostMapping("/matrix-fanout")
    public Response<AppautoMatrixDispatchResult> matrixFanout(
            @RequestBody AppautoMatrixFanoutInput input) {
        Long operator = OperatorContext.currentOperatorId();
        return Response.builderSuccess(matrixService.fanout(
                operator == null ? 0L : operator, input));
    }

    
    @GetMapping("/matrix-model-report")
    public Response<List<AppautoMatrixModelRow>> matrixModelReport(
            @RequestParam long projectId, @RequestParam long caseId) {
        return Response.builderSuccess(matrixService.modelReport(projectId, caseId));
    }
}
