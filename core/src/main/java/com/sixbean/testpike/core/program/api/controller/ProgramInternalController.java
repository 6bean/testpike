package com.sixbean.testpike.core.program.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.program.api.dto.ProgramCreateInput;
import com.sixbean.testpike.core.program.api.dto.ProgramMemberGrantInput;
import com.sixbean.testpike.core.program.api.dto.ProgramMemberInfo;
import com.sixbean.testpike.core.program.api.dto.ProgramMemberRoleInput;
import com.sixbean.testpike.core.program.api.dto.ProgramUpdateInput;
import com.sixbean.testpike.core.program.domain.service.ProgramMemberService;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
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
 * Program (project set) internal endpoints — core service layer, consumed by admin
 * over HTTP (same bridge topology as {@code /internal/projects}).
 *
 * <p>Thin by design: every validation, duplicate check and audit write lives in the
 * services. The operator comes from the X-Operator-Id header forwarded by the caller,
 * exactly like the project bridge; the lens owns no business data so no project-scope
 * guard is needed here (H1 isolation is untouched by construction).
 */
@RestController
@RequestMapping("/internal/programs")
public class ProgramInternalController {

    private final ProgramService programService;
    private final ProgramMemberService memberService;

    public ProgramInternalController(ProgramService programService,
                                     ProgramMemberService memberService) {
        this.programService = programService;
        this.memberService = memberService;
    }

    // ── program lifecycle ───────────────────────────────────────────────────

    /** Create the lens; duplicate active names are rejected by the service. */
    @PostMapping
    public Response<Long> create(@RequestBody ProgramCreateInput input) {
        return Response.builderSuccess(programService.create(
                input.name(), input.description(), OperatorContext.currentOperatorId()));
    }

    /** Rename or re-describe a program. */
    @PutMapping("/{id}")
    public Response<Void> update(@PathVariable long id, @RequestBody ProgramUpdateInput input) {
        programService.update(id, input.name(), input.description(),
                OperatorContext.currentOperatorId());
        return Response.builderSuccess();
    }

    /** Archive (state 0) or restore (state 1); archived programs stop expanding access. */
    @PutMapping("/{id}/state")
    public Response<Void> setState(@PathVariable long id, @RequestParam int state) {
        programService.setState(id, state, OperatorContext.currentOperatorId());
        return Response.builderSuccess();
    }

    /** Delete the lens: only grouping/grant rows disappear, projects are untouched. */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        programService.delete(id, OperatorContext.currentOperatorId());
        return Response.builderSuccess();
    }

    /** Single program view. */
    @GetMapping("/{id}")
    public Response<com.sixbean.testpike.core.program.api.dto.ProgramInfo> get(@PathVariable long id) {
        return Response.builderSuccess(programService.get(id));
    }

    /** Every program (both states); the UI filters per user intent. */
    @GetMapping
    public Response<List<com.sixbean.testpike.core.program.api.dto.ProgramInfo>> list() {
        return Response.builderSuccess(programService.listAll());
    }

    /** Active programs visible to one user (member scope, or everything for a system manager). */
    @GetMapping("/for-user")
    public Response<List<com.sixbean.testpike.core.program.api.dto.UserProgramInfo>> listForUser(
            @RequestParam long userId, @RequestParam boolean systemManager) {
        return Response.builderSuccess(programService.listForUser(userId, systemManager));
    }

    // ── membership of the lens (which projects are inside) ─────────────────

    /** Attach an existing project; re-attach of the same pair is rejected. */
    @PostMapping("/{id}/projects/{projectId}")
    public Response<Void> attachProject(@PathVariable long id, @PathVariable long projectId) {
        programService.attachProject(id, projectId, OperatorContext.currentOperatorId());
        return Response.builderSuccess();
    }

    /** Detach a project from the lens; the project itself is untouched. */
    @DeleteMapping("/{id}/projects/{projectId}")
    public Response<Void> detachProject(@PathVariable long id, @PathVariable long projectId) {
        programService.detachProject(id, projectId, OperatorContext.currentOperatorId());
        return Response.builderSuccess();
    }

    /** Project ids currently inside the lens. */
    @GetMapping("/{id}/projects")
    public Response<List<Long>> listProjectIds(@PathVariable long id) {
        return Response.builderSuccess(programService.listProjectIds(id));
    }

    // ── group-level grants (who may see the projects inside) ───────────────

    /** Grant group membership: role 1 = member, 2 = program owner. */
    @PostMapping("/{id}/members")
    public Response<Void> addMember(@PathVariable long id,
                                    @RequestBody ProgramMemberGrantInput input) {
        memberService.add(id, input.userId(), input.role(), OperatorContext.currentOperatorId());
        return Response.builderSuccess();
    }

    /** Change the granted role of an existing grant. */
    @PutMapping("/{id}/members/{userId}")
    public Response<Void> updateMemberRole(@PathVariable long id, @PathVariable long userId,
                                           @RequestBody ProgramMemberRoleInput input) {
        memberService.updateRole(id, userId, input.role(), OperatorContext.currentOperatorId());
        return Response.builderSuccess();
    }

    /** Revoke the grant; expanded access disappears on the next evaluation. */
    @DeleteMapping("/{id}/members/{userId}")
    public Response<Void> removeMember(@PathVariable long id, @PathVariable long userId) {
        memberService.remove(id, userId, OperatorContext.currentOperatorId());
        return Response.builderSuccess();
    }

    /** Group grant rows of one program. */
    @GetMapping("/{id}/members")
    public Response<List<ProgramMemberInfo>> listMembers(@PathVariable long id) {
        return Response.builderSuccess(memberService.list(id));
    }
}
