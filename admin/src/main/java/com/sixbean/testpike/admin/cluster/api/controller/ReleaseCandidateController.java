package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ReleaseCandidateDismissRequest;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseCandidateView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseManifestView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseRegisterRequest;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreReleaseCandidateClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Release candidate admin bridge (upgrade-center awareness surface, pack C).
 * <p>Permission keys come from the catalog (view=perm:cluster:release:candidate:view
 * for list/badge, write=perm:cluster:release:candidate:confirm for promotion;
 * dismissal is a disposal act on the candidate row and shares the confirm key —
 * ruling #2 keeps publishing authority separate from plain manifest viewing).
 * <p>Thin bridge: validation single point (version match / blank reason / disposed
 * row rejection) lives in core ReleaseCandidateService — no second check here
 * (red line 6), human-worded errors propagate untouched.
 */
@RestController
@RequestMapping("/api/v1/cluster/release-candidates")
public class ReleaseCandidateController {

    private final CoreReleaseCandidateClient coreReleaseCandidateClient;

    public ReleaseCandidateController(CoreReleaseCandidateClient coreReleaseCandidateClient) {
        this.coreReleaseCandidateClient = coreReleaseCandidateClient;
    }

    /** Pending candidates (version-descending = pending-card order; empty list = honest empty state). */
    @GetMapping
    @RequirePermission("perm:cluster:release:candidate:view")
    public Response<List<ReleaseCandidateView>> list() {
        return Response.builderSuccess(coreReleaseCandidateClient.listPending());
    }

    /** Pending count (menu badge metric; 0 renders as honest zero, never null-swallowed). */
    @GetMapping("/pending-count")
    @RequirePermission("perm:cluster:release:candidate:view")
    public Response<Integer> pendingCount() {
        return Response.builderSuccess(coreReleaseCandidateClient.pendingCount());
    }

    /** Confirm a candidate = publish through the core register validation single point
     * (input reuses the seven-field register shape; version mismatch rejected in plain words). */
    @PostMapping("/{id}/confirm")
    @RequirePermission("perm:cluster:release:candidate:confirm")
    public Response<ReleaseManifestView> confirm(@PathVariable long id,
                                                 @RequestBody ReleaseRegisterRequest request) {
        return Response.builderSuccess(coreReleaseCandidateClient.confirm(id, request));
    }

    /** Dismiss a candidate (blank reason rejected by core in plain words). */
    @PostMapping("/{id}/dismiss")
    @RequirePermission("perm:cluster:release:candidate:confirm")
    public Response<ReleaseCandidateView> dismiss(@PathVariable long id,
                                                  @RequestBody ReleaseCandidateDismissRequest request) {
        return Response.builderSuccess(coreReleaseCandidateClient.dismiss(id, request.reason()));
    }
}
