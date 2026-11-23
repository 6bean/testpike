package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoNlStepView;
import com.sixbean.testpike.admin.appauto.api.dto.NlStepGenerateRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/appauto")
public class AppautoNlStepController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoNlStepController(CoreAppautoClient coreAppautoClient,
                                   ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @PostMapping("/nl-step")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoNlStepView> generate(@RequestBody NlStepGenerateRequest request) {
        guard.assertCanView(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreAppautoClient.generateNlStep(
                request.projectId(), request.engineCode(), request.description()));
    }
}
