package com.sixbean.testpike.admin.apitest.api.controller;

import com.sixbean.testpike.admin.apitest.api.dto.AuthPresetDto;
import com.sixbean.testpike.admin.apitest.api.dto.EffectivePreviewDto;
import com.sixbean.testpike.admin.apitest.api.dto.PromotionResultDto;
import com.sixbean.testpike.admin.apitest.api.dto.RequestSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RequestView;
import com.sixbean.testpike.admin.apitest.app.component.ApiTestAccessResolver;
import com.sixbean.testpike.admin.apitest.infrastructure.remote.client.CoreApiRequestClient;
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
@RequestMapping("/api/v1/apitest/requests")
public class ApiRequestController {

    private final CoreApiRequestClient coreApiRequestClient;
    private final ApiTestAccessResolver accessResolver;
    private final ProjectAccessGuard guard;

    public ApiRequestController(CoreApiRequestClient coreApiRequestClient,
                                ApiTestAccessResolver accessResolver,
                                ProjectAccessGuard guard) {
        this.coreApiRequestClient = coreApiRequestClient;
        this.accessResolver = accessResolver;
        this.guard = guard;
    }

    
    @GetMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<List<RequestView>> list(@RequestParam long collectionId) {
        guard.assertCanView(UserContext.current(), accessResolver.projectIdOfCollection(collectionId));
        return Response.builderSuccess(coreApiRequestClient.listRequests(collectionId));
    }

    /**
 * Persist the user-maintained member order of a collection :
 * the drag order on the left rail becomes the launch-preview and whole-set
 * execution order. Full id list required; stale/foreign sets get a 400
 * human message from the core single validation point.
 */
    @PutMapping("/{collectionId}/order")
    @RequirePermission("perm:detail:case-tag")
    public Response<Void> reorder(@PathVariable long collectionId,
                                  @RequestBody List<Long> orderedRequestIds) {
        long projectId = accessResolver.projectIdOfCollection(collectionId);
        guard.assertCanWriteCase(UserContext.current(), projectId);
        coreApiRequestClient.reorderRequests(collectionId, orderedRequestIds);
        return Response.builderSuccess(null);
    }

    
    @PostMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<RequestView> create(@RequestBody RequestSaveRequest request) {
        long projectId = accessResolver.projectIdOfCollection(request.collectionId());
        guard.assertCanWriteCase(UserContext.current(), projectId);
        return Response.builderSuccess(coreApiRequestClient.createRequest(request));
    }

    
    @PutMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<RequestView> update(@PathVariable long id,
                                        @RequestBody RequestSaveRequest request) {
        guard.assertCanWriteCase(UserContext.current(), accessResolver.projectIdOfRequest(id));
        return Response.builderSuccess(coreApiRequestClient.updateRequest(id, request));
    }

    
    @DeleteMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<Void> delete(@PathVariable long id) {
        guard.assertCanWriteCase(UserContext.current(), accessResolver.projectIdOfRequest(id));
        coreApiRequestClient.deleteRequest(id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<RequestView> get(@PathVariable long id) {
        guard.assertCanView(UserContext.current(), accessResolver.projectIdOfRequest(id));
        return Response.builderSuccess(coreApiRequestClient.getRequest(id));
    }

    /**
 * Effective-defaults preview (GET /api/v1/apitest/requests/{id}/effective,
 * : merged headers + resolved auth with per-piece provenance
 * for the editor's final-effective panel. Judgement lives in the core
 * resolver single source; admin bridges verbatim behind the same view
 * guard as request detail (H1 isolation, zero second mechanism).
 */
    @GetMapping("/{id}/effective")
    @RequirePermission("perm:detail:case-tag")
    public Response<EffectivePreviewDto> effective(@PathVariable long id) {
        guard.assertCanView(UserContext.current(), accessResolver.projectIdOfRequest(id));
        return Response.builderSuccess(coreApiRequestClient.effectivePreview(id));
    }

    /**
 * Batch effective-defaults preview (GET /api/v1/apitest/requests/effective-batch
 * ?collectionId=, : one round trip for the whole interface set's
 * merged headers/auth, feeding the perf-wizard import panel at worst scale.
 * Judgement lives in the core resolver single source; admin bridges verbatim
 * behind the same view guard as the collection's request list (H1 isolation,
 * ownership resolved from the collection — never from a client-supplied project).
 */
    @GetMapping("/effective-batch")
    @RequirePermission("perm:detail:case-tag")
    public Response<List<EffectivePreviewDto>> effectiveBatch(@RequestParam long collectionId) {
        guard.assertCanView(UserContext.current(), accessResolver.projectIdOfCollection(collectionId));
        return Response.builderSuccess(coreApiRequestClient.effectivePreviewBatch(collectionId));
    }

    /**
     * Signature auth preset catalogue (GET /api/v1/apitest/requests/auth-presets).
     * Read-only dropdown data for the auth editor: templates carry ${gen.*} functions
     * and {{CRED:key}} references only — never secret values — so no project
     * ownership applies (same posture as static contract metadata). Judgement lives
     * in core AuthPresetCatalog; admin bridges verbatim.
     */
    @GetMapping("/auth-presets")
    @RequirePermission("perm:detail:case-tag")
    public Response<List<AuthPresetDto>> authPresets() {
        return Response.builderSuccess(coreApiRequestClient.listAuthPresets());
    }

    /**
     * Promote one tuned sandbox request into a regression case (02 contract
     * POST /{id}/promote). Write gate = same as request editing; project
     * ownership is resolved from the request itself (never trust a client-supplied
     * project). All promotion judgement lives in core PromotionService —
     * a 40904 repeat-promotion answer naming the existing case number is passed
     * through verbatim (no second check on the admin side).
     */
    @PostMapping("/{id}/promote")
    @RequirePermission("perm:detail:case-tag")
    public Response<PromotionResultDto> promote(@PathVariable long id) {
        guard.assertCanWriteCase(UserContext.current(), accessResolver.projectIdOfRequest(id));
        return Response.builderSuccess(coreApiRequestClient.promoteRequest(id));
    }
}
