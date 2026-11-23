package com.sixbean.testpike.admin.project.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.ProjectTemplateView;
import com.sixbean.testpike.admin.project.api.dto.SaveProjectTemplateRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/project-templates")
public class ProjectTemplateController {

    private final CoreProjectClient coreProjectClient;
    private final ProjectAccessGuard guard;

    public ProjectTemplateController(CoreProjectClient coreProjectClient, ProjectAccessGuard guard) {
        this.coreProjectClient = coreProjectClient;
        this.guard = guard;
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping
    public Response<List<ProjectTemplateView>> list() {
        return Response.builderSuccess(coreProjectClient.listTemplates());
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping("/{id}")
    public Response<ProjectTemplateView> detail(@PathVariable long id) {
        return Response.builderSuccess(coreProjectClient.getTemplate(id));
    }

    
    @RequirePermission("perm:project:project-view")
    @PostMapping
    public Response<Long> create(@RequestBody SaveProjectTemplateRequest request) {
        guard.assertSystemAdminOnly(UserContext.current(), "新建行业模板");
        return Response.builderSuccess(coreProjectClient.createTemplate(request));
    }

    
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{id}")
    public Response<ProjectTemplateView> update(@PathVariable long id,
                                                @RequestBody SaveProjectTemplateRequest request) {
        guard.assertSystemAdminOnly(UserContext.current(), "编辑行业模板");
        return Response.builderSuccess(coreProjectClient.updateTemplate(id, request));
    }

    
    @RequirePermission("perm:project:project-view")
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        guard.assertSystemAdminOnly(UserContext.current(), "删除行业模板");
        coreProjectClient.deleteTemplate(id);
        return Response.builderSuccess(null);
    }
}
