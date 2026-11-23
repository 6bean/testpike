package com.sixbean.testpike.admin.program.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.program.api.dto.CreateProgramRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberAddRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberRoleRequest;
import com.sixbean.testpike.admin.program.api.dto.ProgramMemberView;
import com.sixbean.testpike.admin.program.api.dto.ProgramView;
import com.sixbean.testpike.admin.program.api.dto.UpdateProgramRequest;
import com.sixbean.testpike.admin.program.infrastructure.remote.client.CoreProgramClient;
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

/**
 * Program (project set) admin endpoints — the user-facing half of the lens bridge
 * (admin /api/v1/** → core /internal/programs/**, same topology as ProjectController).
 * <p>Thin forwarding by design: validation, duplicate checks, role expansion and the
 * group-grant audit all live in core. Requests are passed through as whole objects —
 * never rebuilt field by field (see ProgramBridgeTest).
 * <p>Permission: reuses the K12 {@code perm:project:project-view} VIEW-family key, the
 * same zero-drift pattern as the appauto bridge controllers; a dedicated
 * {@code perm:program:*} family needs RTM feature-point rows, which the wave closing
 * package registers — this chain keeps the bookkeeping files untouched.
 */
@RestController
@RequestMapping("/api/v1/programs")
public class ProgramController {

    private final CoreProgramClient coreProgramClient;

    public ProgramController(CoreProgramClient coreProgramClient) {
        this.coreProgramClient = coreProgramClient;
    }

    /** Every program (both states); the UI filters per user intent. */
    @RequirePermission("perm:project:project-view")
    @GetMapping
    public Response<List<ProgramView>> list() {
        requireLogin();
        return Response.builderSuccess(coreProgramClient.listPrograms());
    }

    /** Single program view. */
    @RequirePermission("perm:project:project-view")
    @GetMapping("/{id}")
    public Response<ProgramView> get(@PathVariable long id) {
        requireLogin();
        return Response.builderSuccess(coreProgramClient.getProgram(id));
    }

    /** Create the lens; duplicate active names are rejected by core. */
    @RequirePermission("perm:project:project-view")
    @PostMapping
    public Response<Long> create(@RequestBody CreateProgramRequest request) {
        requireLogin();
        return Response.builderSuccess(coreProgramClient.createProgram(request));
    }

    /** Rename or re-describe a program. */
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{id}")
    public Response<Void> update(@PathVariable long id, @RequestBody UpdateProgramRequest request) {
        requireLogin();
        coreProgramClient.updateProgram(id, request);
        return Response.builderSuccess();
    }

    /** Archive (state 0) or restore (state 1); archived programs stop expanding access. */
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{id}/state")
    public Response<Void> setState(@PathVariable long id, @RequestParam int state) {
        requireLogin();
        coreProgramClient.setProgramState(id, state);
        return Response.builderSuccess();
    }

    /** Delete the lens: only grouping/grant rows disappear, projects are untouched. */
    @RequirePermission("perm:project:project-view")
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        requireLogin();
        coreProgramClient.deleteProgram(id);
        return Response.builderSuccess();
    }

    /** Attach an existing project to the lens. */
    @RequirePermission("perm:project:project-view")
    @PostMapping("/{id}/projects/{projectId}")
    public Response<Void> attachProject(@PathVariable long id, @PathVariable long projectId) {
        requireLogin();
        coreProgramClient.attachProject(id, projectId);
        return Response.builderSuccess();
    }

    /** Detach a project from the lens; the project itself is untouched. */
    @RequirePermission("perm:project:project-view")
    @DeleteMapping("/{id}/projects/{projectId}")
    public Response<Void> detachProject(@PathVariable long id, @PathVariable long projectId) {
        requireLogin();
        coreProgramClient.detachProject(id, projectId);
        return Response.builderSuccess();
    }

    /** Project ids currently inside the lens. */
    @RequirePermission("perm:project:project-view")
    @GetMapping("/{id}/projects")
    public Response<List<Long>> listProjectIds(@PathVariable long id) {
        requireLogin();
        return Response.builderSuccess(coreProgramClient.listProgramProjectIds(id));
    }

    /** Grant group membership: role 1 = member, 2 = program owner. */
    @RequirePermission("perm:project:project-view")
    @PostMapping("/{id}/members")
    public Response<Void> addMember(@PathVariable long id,
                                    @RequestBody ProgramMemberAddRequest request) {
        requireLogin();
        coreProgramClient.addMember(id, request);
        return Response.builderSuccess();
    }

    /** Change the granted role of an existing grant. */
    @RequirePermission("perm:project:project-view")
    @PutMapping("/{id}/members/{userId}")
    public Response<Void> updateMemberRole(@PathVariable long id, @PathVariable long userId,
                                           @RequestBody ProgramMemberRoleRequest request) {
        requireLogin();
        coreProgramClient.updateMemberRole(id, userId, request);
        return Response.builderSuccess();
    }

    /** Revoke the grant; expanded access disappears on the next evaluation. */
    @RequirePermission("perm:project:project-view")
    @DeleteMapping("/{id}/members/{userId}")
    public Response<Void> removeMember(@PathVariable long id, @PathVariable long userId) {
        requireLogin();
        coreProgramClient.removeMember(id, userId);
        return Response.builderSuccess();
    }

    /** Group grant rows of one program. */
    @RequirePermission("perm:project:project-view")
    @GetMapping("/{id}/members")
    public Response<List<ProgramMemberView>> listMembers(@PathVariable long id) {
        requireLogin();
        return Response.builderSuccess(coreProgramClient.listMembers(id));
    }

    /** Anonymous callers get the framework 401 before any core hop (same guard surface
     *  as UserContext.current() elsewhere; kept as one private step, no duplication). */
    private void requireLogin() {
        UserContext.current();
    }
}
