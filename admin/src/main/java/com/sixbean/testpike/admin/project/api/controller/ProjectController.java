package com.sixbean.testpike.admin.project.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.CreateProjectRequest;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.api.dto.ProjectView;
import com.sixbean.testpike.admin.project.api.dto.UpdateProjectRequest;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.CoreProjectClient;
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


@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final CoreProjectClient coreProjectClient;
    private final ProjectAccessGuard guard;

    public ProjectController(CoreProjectClient coreProjectClient, ProjectAccessGuard guard) {
        this.coreProjectClient = coreProjectClient;
        this.guard = guard;
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping
    public Response<PageView<ProjectView>> list(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(required = false) String sortBy,
                                                @RequestParam(required = false) String sortDir) {
        boolean allVisible = guard.isSystemManager(UserContext.current());
        Integer effectiveStatus = status != null ? status : 1;
        return Response.builderSuccess(
                coreProjectClient.listProjects(allVisible, keyword, effectiveStatus, page, size, sortBy, sortDir));
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping("/{id}")
    public Response<ProjectView> get(@PathVariable long id) {
        var user = UserContext.current();
        guard.assertCanView(user, id);
        return Response.builderSuccess(coreProjectClient.getProject(id));
    }

    
    @RequirePermission("perm:project:project-view")
    @PostMapping
    public Response<Long> create(@RequestBody CreateProjectRequest request) {
        guard.assertCanCreate(UserContext.current());
        return Response.builderSuccess(coreProjectClient.createProject(request));
    }

    
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{id}")
    public Response<ProjectView> update(@PathVariable long id, @RequestBody UpdateProjectRequest request) {
        guard.assertCanManage(UserContext.current(), id);
        return Response.builderSuccess(coreProjectClient.updateProject(id, request));
    }

    
    @RequirePermission("perm:project:project-view")
    @DeleteMapping("/{id}")
    public Response<ProjectView> archive(@PathVariable long id) {
        guard.assertCanArchive(UserContext.current());
        return Response.builderSuccess(coreProjectClient.archiveProject(id));
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping("/archived")
    public Response<PageView<ProjectView>> archived(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "10") int size) {
        guard.assertCanArchive(UserContext.current());
        return Response.builderSuccess(coreProjectClient.listArchived(page, size));
    }

    
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{id}/restore")
    public Response<ProjectView> restore(@PathVariable long id) {
        guard.assertCanArchive(UserContext.current());
        return Response.builderSuccess(coreProjectClient.restoreProject(id));
    }

 
    @RequirePermission("perm:project:project-view")
    @DeleteMapping("/{id}/purge")
    public Response<Void> purge(@PathVariable long id,
                                @RequestParam(defaultValue = "false") boolean force) {
        guard.assertCanArchive(UserContext.current());
        coreProjectClient.purgeProject(id, force);
        return Response.builderSuccess(null);
    }
}
