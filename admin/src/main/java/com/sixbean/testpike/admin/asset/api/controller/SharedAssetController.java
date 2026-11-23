package com.sixbean.testpike.admin.asset.api.controller;

import com.sixbean.testpike.admin.asset.api.dto.AssetPinForm;
import com.sixbean.testpike.admin.asset.api.dto.AssetPublishForm;
import com.sixbean.testpike.admin.asset.api.dto.AssetReferenceView;
import com.sixbean.testpike.admin.asset.api.dto.AssetVersionTimelineView;
import com.sixbean.testpike.admin.asset.api.dto.SharedAssetView;
import com.sixbean.testpike.admin.asset.infrastructure.remote.client.CoreSharedAssetClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shared asset pool user-facing doors tail, publish-reference
 * system of the cross-project asset pool).
 * <p>Two-layer permission, inventory-bridge precedent: a functional key opens
 * the door (pool read = VIEW family, publish = CREATE, withdraw = APPROVE,
 * pin = EDIT), and the project fence decides who may WRITE inside a project —
 * publish/withdraw need the project lead (H guard), pin rides the case-write
 * guard because the pin host is case-editor content (developers stay read-only,
 * the guard family is reused, not re-implemented, R40). The owner fence itself
 * lives in core and is re-checked there against callerProjectId: a bypassed
 * admin guard could still not write into another project's chain.
 * <p>Read doors are pool-global on purpose (global discoverability is the
 * product rule of the pool, 16 row): the published-head list, a version
 * row and both bidirectional reference directions are readable by anyone
 * holding the pool read key; head/stale math is never recomputed here.
 */
@RestController
@RequestMapping("/api/v1/shared-assets")
public class SharedAssetController {

    private final CoreSharedAssetClient coreSharedAssetClient;
    private final ProjectAccessGuard guard;

    public SharedAssetController(CoreSharedAssetClient coreSharedAssetClient,
                                 ProjectAccessGuard guard) {
        this.coreSharedAssetClient = coreSharedAssetClient;
        this.guard = guard;
    }

    /** Publish (or republish = version bump) one asset from the acting project. */
    @PostMapping
    @RequirePermission("perm:asset:publish")
    public Response<SharedAssetView> publish(@RequestBody AssetPublishForm form) {
        CurrentUser user = UserContext.current();
        guard.assertCanManage(user, form.projectId());
        return Response.builderSuccess(coreSharedAssetClient.publish(form.projectId(), form));
    }

    /** Read one version row; superseded rows stay resolvable so pinned views work. */
    @GetMapping("/{id}")
    @RequirePermission("perm:asset:pool:read")
    public Response<SharedAssetView> get(@PathVariable long id) {
        return Response.builderSuccess(coreSharedAssetClient.get(id));
    }

    /** Globally discoverable published heads, optional kind filter, paged. */
    @GetMapping
    @RequirePermission("perm:asset:pool:read")
    public Response<PageView<SharedAssetView>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(coreSharedAssetClient.list(kind, page, size, sortBy, sortDir));
    }

    /** Withdraw the whole chain from the pool; lead of the owner project only. */
    @PostMapping("/{id}/withdraw")
    @RequirePermission("perm:asset:withdraw")
    public Response<Void> withdraw(@RequestParam long projectId, @PathVariable long id,
                                   @RequestParam(defaultValue = "false") boolean force) {
        CurrentUser user = UserContext.current();
        guard.assertCanManage(user, projectId);
        coreSharedAssetClient.withdraw(projectId, id, force);
        return Response.builderSuccess(null);
    }

    /** Register or move the acting project's host pin to a concrete version row. */
    @PostMapping("/references")
    @RequirePermission("perm:asset:pin")
    public Response<AssetReferenceView> pin(@RequestBody AssetPinForm form) {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteCase(user, form.projectId());
        return Response.builderSuccess(coreSharedAssetClient.pin(form.projectId(), form));
    }

    /** Owner direction "who uses me": every pin across all versions of one chain. */
    @GetMapping("/references/to-chain")
    @RequirePermission("perm:asset:pool:read")
    public Response<PageView<AssetReferenceView>> referencesToChain(
            @RequestParam long chainProjectId,
            @RequestParam String kind,
            @RequestParam String name,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(coreSharedAssetClient.referencesToChain(
                chainProjectId, kind, name, page, size));
    }

    /** Consumer direction "what I use": every pin held by one project. */
    @GetMapping("/references/by-consumer")
    @RequirePermission("perm:asset:pool:read")
    public Response<PageView<AssetReferenceView>> referencesByConsumer(
            @RequestParam long consumerProjectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(coreSharedAssetClient.referencesByConsumer(
                consumerProjectId, page, size));
    }

    /** Version timeline of one chain: per-version pin counts, newest first. */
    @GetMapping("/references/chain-versions")
    @RequirePermission("perm:asset:pool:read")
    public Response<List<AssetVersionTimelineView>> chainVersions(
            @RequestParam long chainProjectId,
            @RequestParam String kind,
            @RequestParam String name) {
        return Response.builderSuccess(
                coreSharedAssetClient.chainVersions(chainProjectId, kind, name));
    }
}
