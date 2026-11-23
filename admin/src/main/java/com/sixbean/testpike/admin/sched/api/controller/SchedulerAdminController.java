package com.sixbean.testpike.admin.sched.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.sched.api.dto.SchedulerHealthView;
import com.sixbean.testpike.admin.sched.app.service.SchedulerAdminService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/scheduler")
public class SchedulerAdminController {

    private final SchedulerAdminService schedulerAdminService;

    public SchedulerAdminController(SchedulerAdminService schedulerAdminService) {
        this.schedulerAdminService = schedulerAdminService;
    }

    
    @GetMapping("/jobs")
    @RequirePermission("perm:system:system-config")
    public Response<List<String>> jobs() {
        return Response.builderSuccess(schedulerAdminService.jobs());
    }

    
    @PostMapping("/run-now")
    @RequirePermission("perm:system:system-config")
    public Response<String> runNow(@RequestParam String job) {
        return Response.builderSuccess(
                schedulerAdminService.run(job, UserContext.currentUserId()));
    }

    /** Paged health read (catalog x lock state; keyword is optional, sortBy/sortDir is the optional core whitelist key). */
    @GetMapping("/health")
    @RequirePermission("perm:system:system-config")
    public Response<PageView<SchedulerHealthView>> health(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                schedulerAdminService.health(keyword, page, size, sortBy, sortDir));
    }
}
