package com.sixbean.testpike.admin.project.api.controller;

import com.sixbean.testpike.admin.project.api.dto.MemberRoleRequest;

import com.sixbean.testpike.admin.project.api.dto.MemberAddRequest;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.MemberView;
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
@RequestMapping("/api/v1/projects/{projectId}/members")
public class MemberController {

    private final CoreProjectClient coreProjectClient;
    private final ProjectAccessGuard guard;

    public MemberController(CoreProjectClient coreProjectClient, ProjectAccessGuard guard) {
        this.coreProjectClient = coreProjectClient;
        this.guard = guard;
    }

    
    @RequirePermission("perm:project:project-view")
    @GetMapping
    public Response<List<MemberView>> list(@PathVariable long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreProjectClient.listMembers(projectId));
    }

    
    @RequirePermission("perm:project:project-view")
    @PostMapping
    public Response<Void> add(@PathVariable long projectId, @RequestBody MemberAddRequest request) {
        guard.assertCanManage(UserContext.current(), projectId);
        coreProjectClient.addMember(projectId, request);
        return Response.builderSuccess();
    }

    
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{userId}")
    public Response<Void> updateRole(@PathVariable long projectId, @PathVariable long userId,
                                     @RequestBody MemberRoleRequest request) {
        guard.assertCanManage(UserContext.current(), projectId);
        coreProjectClient.updateMemberRole(projectId, userId, request);
        return Response.builderSuccess();
    }

    
    @RequirePermission("perm:project:project-view")
    @DeleteMapping("/{userId}")
    public Response<Void> remove(@PathVariable long projectId, @PathVariable long userId) {
        guard.assertCanManage(UserContext.current(), projectId);
        coreProjectClient.removeMember(projectId, userId);
        return Response.builderSuccess();
    }
}
