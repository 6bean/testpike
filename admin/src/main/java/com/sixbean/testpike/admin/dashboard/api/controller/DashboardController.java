package com.sixbean.testpike.admin.dashboard.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.dashboard.api.dto.DashboardOverviewView;
import com.sixbean.testpike.admin.dashboard.infrastructure.remote.client.CoreDashboardClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final CoreDashboardClient coreDashboardClient;
    private final ProjectAccessGuard guard;

    public DashboardController(CoreDashboardClient coreDashboardClient, ProjectAccessGuard guard) {
        this.coreDashboardClient = coreDashboardClient;
        this.guard = guard;
    }

    
    @GetMapping("/overview")
    public Response<DashboardOverviewView> overview() {
        CurrentUser user = UserContext.current();
        boolean allVisible = guard.isSystemManager(user);
        return Response.builderSuccess(coreDashboardClient.overview(allVisible));
    }
}
