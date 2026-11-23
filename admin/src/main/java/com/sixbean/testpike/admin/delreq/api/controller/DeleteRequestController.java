package com.sixbean.testpike.admin.delreq.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestCreateRequest;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestRejectRequest;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestView;
import com.sixbean.testpike.admin.delreq.infrastructure.remote.client.DeleteRequestClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <ul>
 * </ul>
 *
 */
@RestController
@RequestMapping("/api/v1/delete-requests")
public class DeleteRequestController {

    private final DeleteRequestClient deleteRequestClient;
    private final ProjectAccessGuard projectAccessGuard;

    public DeleteRequestController(DeleteRequestClient deleteRequestClient,
                                   ProjectAccessGuard projectAccessGuard) {
        this.deleteRequestClient = deleteRequestClient;
        this.projectAccessGuard = projectAccessGuard;
    }

    /**
     * Project-scoped delete-request list (2=pending 3=approved 4=rejected;
     * null status = all). The optional sortBy/sortDir pair forwards to the
     * core whitelist — the bridge owns no second gate.
     */
    @GetMapping
    public Response<PageView<DeleteRequestView>> list(@RequestParam long projectId,
                                                      @RequestParam(required = false) Integer status,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String sortBy,
                                                      @RequestParam(required = false) String sortDir) {
        long operatorId = UserContext.currentUserId();
        CurrentUser user = UserContext.current();
        projectAccessGuard.assertCanView(user, projectId);
        return Response.builderSuccess(
                deleteRequestClient.list(projectId, status, page, size, sortBy, sortDir, operatorId));
    }

    
    @PostMapping
    public Response<DeleteRequestView> create(@RequestBody DeleteRequestCreateRequest request) {
        long operatorId = UserContext.currentUserId();
        CurrentUser user = UserContext.current();
        if (request.projectId() != null) {
            projectAccessGuard.assertCanDeleteCase(user, request.projectId());
        }
        return Response.builderSuccess(deleteRequestClient.create(operatorId, request));
    }

    
    @PostMapping("/{id}/approve")
    public Response<DeleteRequestView> approve(@PathVariable long id) {
        long operatorId = UserContext.currentUserId();
        CurrentUser user = UserContext.current();
        DeleteRequestView request = deleteRequestClient.get(id, operatorId);
        projectAccessGuard.assertCanManage(user, request.projectId());
        return Response.builderSuccess(deleteRequestClient.approve(id, operatorId));
    }

    
    @PostMapping("/{id}/reject")
    public Response<DeleteRequestView> reject(@PathVariable long id,
                                              @RequestBody DeleteRequestRejectRequest request) {
        long operatorId = UserContext.currentUserId();
        CurrentUser user = UserContext.current();
        DeleteRequestView existing = deleteRequestClient.get(id, operatorId);
        projectAccessGuard.assertCanManage(user, existing.projectId());
        return Response.builderSuccess(deleteRequestClient.reject(id, operatorId, request));
    }
}
