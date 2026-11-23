package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/appauto")
public class AppautoStabilityController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoStabilityController(CoreAppautoClient coreAppautoClient,
                                      ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @GetMapping("/stability-rows")
    @RequirePermission("perm:project:project-view")
    public Response<List<Map<String, Object>>> stabilityRows(
            @RequestParam long projectId,
            @RequestParam long caseId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreAppautoClient.listStabilityRows(projectId, caseId));
    }
}
