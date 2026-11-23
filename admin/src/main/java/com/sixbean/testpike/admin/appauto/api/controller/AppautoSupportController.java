package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoSupportView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeAdviceRequest;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeAdviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeFlakyLinkView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoUpgradeWindowView;
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
public class AppautoSupportController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoSupportController(CoreAppautoClient coreAppautoClient,
                                    ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @GetMapping("/engine-support")
    @RequirePermission("perm:project:project-view")
    public Response<List<AppautoSupportView>> engineSupport(@RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreAppautoClient.engineSupport(projectId));
    }

    
    @PostMapping("/upgrade-advice")
    @RequirePermission("perm:appauto:advisor:run")
    public Response<AppautoUpgradeAdviceView> upgradeAdvice(
            @RequestBody AppautoUpgradeAdviceRequest request) {
        guard.assertCanView(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreAppautoClient.upgradeAdvice(
                request.projectId(), request.manifestVersion()));
    }

    
    @GetMapping("/upgrade-window")
    @RequirePermission("perm:cluster:upgrade:view")
    public Response<List<AppautoUpgradeWindowView>> upgradeWindow() {
        return Response.builderSuccess(coreAppautoClient.upgradeWindow());
    }

    
    @GetMapping("/upgrade-flaky-links")
    @RequirePermission("perm:cluster:upgrade:view")
    public Response<List<AppautoUpgradeFlakyLinkView>> upgradeFlakyLinks() {
        return Response.builderSuccess(coreAppautoClient.upgradeFlakyLinks());
    }
}
