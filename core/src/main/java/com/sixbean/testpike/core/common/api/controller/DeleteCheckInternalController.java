package com.sixbean.testpike.core.common.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.api.dto.DeleteCheckResult;
import com.sixbean.testpike.core.common.domain.component.DeleteGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delete-protection reference check endpoint (interface design contract change: all DELETE requests for referenced objects return 409 with a human-readable message).
 * <p>Before deletion, the frontend first calls delete-check to display a human-readable reference count (e.g., "This project still has 3 environments and 156 test cases").
 * After the user confirms the cascade, the delete endpoint is called with force=true (secondary confirmation).
 *
 */
@RestController
@RequestMapping("/internal/delete-check")
public class DeleteCheckInternalController {

    private final DeleteGuard deleteGuard;

    public DeleteCheckInternalController(DeleteGuard deleteGuard) {
        this.deleteGuard = deleteGuard;
    }

    /** Pre-deletion reference check for projects (counts of environments, test cases, plans, and members via /projects/{id}/delete-check). */
    @GetMapping("/projects/{id}")
    public Response<DeleteCheckResult> checkProject(@PathVariable long id) {
        return Response.builderSuccess(deleteGuard.checkProject(id));
    }

    /** Pre-deletion reference check for environments (number of execution batches). */
    @GetMapping("/envs/{id}")
    public Response<DeleteCheckResult> checkEnv(@PathVariable long id) {
        return Response.builderSuccess(deleteGuard.checkEnv(id));
    }

    /** Pre-deletion reference check for roles (number of assigned users plus built-in role flag). */
    @GetMapping("/roles/{id}")
    public Response<DeleteCheckResult> checkRole(@PathVariable long id) {
        return Response.builderSuccess(deleteGuard.checkRole(id));
    }

    /** Pre-deletion reference check for plans (number of execution batches and deviation records via /plans/{id}/delete-check). */
    @GetMapping("/plans/{id}")
    public Response<DeleteCheckResult> checkPlan(@PathVariable long id) {
        return Response.builderSuccess(deleteGuard.checkPlan(id));
    }

    /** Pre-deletion reference check for tags (number of associated test cases). */
    @GetMapping("/tags/{id}")
    public Response<DeleteCheckResult> checkTag(@PathVariable long id) {
        return Response.builderSuccess(deleteGuard.checkTag(id));
    }

    /** Pre-deletion reference check for test cases (number of execution plans referencing it via /cases/{id}/delete-check). */
    @GetMapping("/cases/{id}")
    public Response<DeleteCheckResult> checkCase(@PathVariable long id) {
        return Response.builderSuccess(deleteGuard.checkCase(id));
    }
}
