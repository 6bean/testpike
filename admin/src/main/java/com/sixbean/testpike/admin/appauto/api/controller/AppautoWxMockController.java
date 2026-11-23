package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoWxMockView;
import com.sixbean.testpike.admin.appauto.api.dto.WxMockGenerateRequest;
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
public class AppautoWxMockController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoWxMockController(CoreAppautoClient coreAppautoClient,
                                   ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @PostMapping("/wx-mock")
    @RequirePermission("perm:appauto:wxmock:run")
    public Response<AppautoWxMockView> generate(@RequestBody WxMockGenerateRequest request) {
        guard.assertCanView(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreAppautoClient.generateWxMock(
                request.projectId(), request.apiPath(), request.method(), request.contract()));
    }
}
