package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.ApiCollectionView;
import com.sixbean.testpike.admin.apitest.api.dto.CollectionSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.CollectionUpdateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.FlowPromotionResultDto;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreApiCollectionClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
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
@RequestMapping("/api/v1/apitest/collections")
public class ApiCollectionController {

    private final CoreApiCollectionClient coreApiCollectionClient;
    private final ProjectAccessGuard guard;

    public ApiCollectionController(CoreApiCollectionClient coreApiCollectionClient,
                                   ProjectAccessGuard guard) {
        this.coreApiCollectionClient = coreApiCollectionClient;
        this.guard = guard;
    }

    
    @GetMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<List<ApiCollectionView>> list(@RequestParam long projectId,
                                                  @RequestParam(defaultValue = "0") long programId) {
        // Program lens view has no single project to guard — the server-side
        // ProgramLensScope already intersects the lens with the caller's visible
        // domain. Skip the per-project guard only when no project is pinned
        // (same posture as the batch bridge).
        if (projectId > 0 || programId <= 0) {
            guard.assertCanView(UserContext.current(), projectId);
        }
        return Response.builderSuccess(coreApiCollectionClient.listCollections(projectId, programId));
    }

    
    @PostMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<ApiCollectionView> create(@RequestBody CollectionSaveRequest request) {
        guard.assertCanWriteCase(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreApiCollectionClient.createCollection(request));
    }

    
    @PutMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<ApiCollectionView> rename(@PathVariable long id,
                                              @RequestBody CollectionUpdateRequest request) {
        ApiCollectionView current = requireCollection(id);
        guard.assertCanWriteCase(UserContext.current(), current.projectId());
        return Response.builderSuccess(coreApiCollectionClient.renameCollection(id, request));
    }

    
    @DeleteMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<Void> delete(@PathVariable long id) {
        ApiCollectionView current = requireCollection(id);
        guard.assertCanWriteCase(UserContext.current(), current.projectId());
        coreApiCollectionClient.deleteCollection(id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<ApiCollectionView> get(@PathVariable long id) {
        ApiCollectionView current = requireCollection(id);
        guard.assertCanView(UserContext.current(), current.projectId());
        return Response.builderSuccess(current);
    }

    /**
 * Promote the collection's whole linear request chain into one regression
 * case . Write gate =
 * same as collection editing; project ownership is resolved from the
 * collection itself (never trust a client-supplied project). All promotion
 * judgement lives in core PromotionService — the 400 honest-refusal for
 * orchestration/ws members and the 40904 already-promoted answer are
 * passed through verbatim (no second check on the admin side).
 */
    @PostMapping("/{id}/promote-flow")
    @RequirePermission("perm:detail:case-tag")
    public Response<FlowPromotionResultDto> promoteFlow(@PathVariable long id) {
        ApiCollectionView current = requireCollection(id);
        guard.assertCanWriteCase(UserContext.current(), current.projectId());
        return Response.builderSuccess(coreApiCollectionClient.promoteFlow(id));
    }

    private ApiCollectionView requireCollection(long collectionId) {
        return coreApiCollectionClient.getCollection(collectionId);
    }
}
