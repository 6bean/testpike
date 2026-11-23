package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixDispatchResult;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixFanoutRequest;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixModelRow;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixPrecheckView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixSampleRequest;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoMatrixSampleView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/appauto")
public class AppautoMatrixController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoMatrixController(CoreAppautoClient coreAppautoClient,
                                   ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @PostMapping("/matrix-precheck")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoMatrixPrecheckView> matrixPrecheck(
            @RequestBody AppautoMatrixFanoutRequest request) {
        guard.assertCanView(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreAppautoClient.matrixPrecheck(request));
    }

    
    @PostMapping("/matrix-fanout")
    @RequirePermission("perm:appauto:matrix:run")
    public Response<AppautoMatrixDispatchResult> matrixFanout(
            @RequestBody AppautoMatrixFanoutRequest request) {
        guard.assertCanView(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreAppautoClient.matrixFanout(request));
    }

    
    @GetMapping("/matrix-model-report")
    @RequirePermission("perm:project:project-view")
    public Response<List<AppautoMatrixModelRow>> matrixModelReport(
            @RequestParam long projectId, @RequestParam long caseId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreAppautoClient.matrixModelReport(projectId, caseId));
    }

    
    @PostMapping("/matrix-sample")
    @RequirePermission("perm:appauto:matrix:run")
    public Response<AppautoMatrixSampleView> matrixSample(
            @RequestBody AppautoMatrixSampleRequest request) {
        guard.assertCanView(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreAppautoClient.matrixSample(request));
    }
}
