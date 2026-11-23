package com.sixbean.testpike.core.dashboard.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.dashboard.api.dto.DashboardOverview;
import com.sixbean.testpike.core.dashboard.domain.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/dashboard")
public class DashboardInternalController {

    private final DashboardService dashboardService;

    public DashboardInternalController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    
    @GetMapping("/overview")
    public Response<DashboardOverview> overview(@RequestParam long userId,
                                                @RequestParam(defaultValue = "false") boolean allVisible) {
        return Response.builderSuccess(dashboardService.overview(userId, allVisible));
    }
}
