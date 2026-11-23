package com.sixbean.testpike.admin.rbac.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;


@RestController
@RequestMapping("/api/v1/me")
public class MePermissionsController {

    private final CoreRoleClient coreRoleClient;

    public MePermissionsController(CoreRoleClient coreRoleClient) {
        this.coreRoleClient = coreRoleClient;
    }

    
    @GetMapping("/permissions")
    public Response<Set<String>> permissions() {
        return Response.builderSuccess(coreRoleClient.permsOfUser(UserContext.currentUserId()));
    }
}
