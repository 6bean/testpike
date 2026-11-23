package com.sixbean.testpike.admin.monitor.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorCheckLogView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorEpisodeView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorLinkedAssetsView;
import com.sixbean.testpike.admin.monitor.api.dto.MonitorTargetView;
import com.sixbean.testpike.admin.monitor.api.dto.SilenceRequest;
import com.sixbean.testpike.admin.monitor.api.dto.TargetSaveRequest;
import com.sixbean.testpike.admin.monitor.infrastructure.remote.client.CoreMonitorClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorTargetController {

    private final CoreMonitorClient coreMonitorClient;
    private final ProjectAccessGuard guard;

    public MonitorTargetController(CoreMonitorClient coreMonitorClient, ProjectAccessGuard guard) {
        this.coreMonitorClient = coreMonitorClient;
        this.guard = guard;
    }

    
    @GetMapping("/targets")
    @RequirePermission("perm:project:project-view")
    public Response<List<MonitorTargetView>> list(@RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreMonitorClient.listTargets(projectId));
    }

    /** Paged target list (wall page server-side paging channel, S-group contract; H1 project isolation). */
    @GetMapping("/targets/page")
    @RequirePermission("perm:project:project-view")
    public Response<com.sixbean.testpike.admin.monitor.api.dto.MonitorTargetPageView> page(
            @RequestParam long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "200") int pageSize,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String keyword) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(
                coreMonitorClient.pageTargets(projectId, page, pageSize, sortBy, sortDir,
                        status, targetType, keyword));
    }

    
    @PostMapping("/targets")
    @RequirePermission("perm:project:project-view")
    public Response<Long> create(@RequestBody TargetSaveRequest request) {
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreMonitorClient.createTarget(request));
    }

    
    @GetMapping("/targets/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<MonitorTargetView> get(@PathVariable long id) {
        MonitorTargetView target = coreMonitorClient.findTarget(id);
        guard.assertCanView(UserContext.current(), target.projectId());
        return Response.builderSuccess(target);
    }

    
    @PutMapping("/targets/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> update(@PathVariable long id, @RequestBody TargetSaveRequest request) {
        MonitorTargetView current = coreMonitorClient.findTarget(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreMonitorClient.updateTarget(id, request);
        return Response.builderSuccess(null);
    }

    
    @PutMapping("/targets/{id}/enabled")
    @RequirePermission("perm:project:project-view")
    public Response<Void> toggle(@PathVariable long id, @RequestParam boolean enabled) {
        MonitorTargetView current = coreMonitorClient.findTarget(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreMonitorClient.updateEnabled(id, enabled);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/targets/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> delete(@PathVariable long id) {
        MonitorTargetView current = coreMonitorClient.findTarget(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreMonitorClient.deleteTarget(id);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/targets/{id}/silence")
    @RequirePermission("perm:project:project-view")
    public Response<Void> silence(@PathVariable long id, @RequestBody SilenceRequest request) {
        MonitorTargetView current = coreMonitorClient.findTarget(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreMonitorClient.silence(id, request);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/targets/{id}/silence")
    @RequirePermission("perm:project:project-view")
    public Response<Void> clearSilence(@PathVariable long id) {
        MonitorTargetView current = coreMonitorClient.findTarget(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreMonitorClient.clearSilence(id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/targets/{id}/check-logs")
    @RequirePermission("perm:project:project-view")
    public Response<List<MonitorCheckLogView>> checkLogs(@PathVariable long id,
                                                         @RequestParam(defaultValue = "0") int limit) {
        MonitorTargetView current = coreMonitorClient.findTarget(id);
        guard.assertCanView(UserContext.current(), current.projectId());
        return Response.builderSuccess(coreMonitorClient.checkLogs(id, limit));
    }

    
    @GetMapping("/environments/{envId}/open-episode")
    @RequirePermission("perm:project:project-view")
    public Response<MonitorEpisodeView> openEpisode(@RequestParam long projectId,
                                                    @PathVariable long envId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreMonitorClient.openEpisode(envId));
    }

    
    @GetMapping("/environments/{envId}/linked-assets")
    @RequirePermission("perm:project:project-view")
    public Response<MonitorLinkedAssetsView> linkedAssets(@RequestParam long projectId,
                                                          @PathVariable long envId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreMonitorClient.linkedAssets(envId));
    }
}
