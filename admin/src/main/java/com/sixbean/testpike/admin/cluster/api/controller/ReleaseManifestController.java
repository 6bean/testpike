package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ExecutorCompatView;
import com.sixbean.testpike.admin.cluster.api.dto.PlatformVersionView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseManifestView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseRegisterRequest;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreReleaseManifestClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/cluster/release-manifests")
public class ReleaseManifestController {

    private final CoreReleaseManifestClient coreReleaseManifestClient;

    public ReleaseManifestController(CoreReleaseManifestClient coreReleaseManifestClient) {
        this.coreReleaseManifestClient = coreReleaseManifestClient;
    }

    
    @PostMapping
    @RequirePermission("perm:cluster:upgrade:run")
    public Response<ReleaseManifestView> register(@RequestBody ReleaseRegisterRequest request) {
        return Response.builderSuccess(coreReleaseManifestClient.register(request));
    }

    
    @GetMapping("/latest")
    @RequirePermission("perm:cluster:manifest:view")
    public Response<ReleaseManifestView> latest() {
        return Response.builderSuccess(coreReleaseManifestClient.latest());
    }

    
    @GetMapping
    @RequirePermission("perm:cluster:manifest:view")
    public Response<List<ReleaseManifestView>> list() {
        return Response.builderSuccess(coreReleaseManifestClient.listAll());
    }

    
    @GetMapping("/compat-diff")
    @RequirePermission("perm:cluster:manifest:view")
    public Response<List<ExecutorCompatView>> compatDiff() {
        return Response.builderSuccess(coreReleaseManifestClient.compatDiff());
    }

    /**
     * Hypothetical-range impact pre-check for the candidate confirm drawer — forwards
     * min/max verbatim; the out-of-range verdict is core-side single source (the
     * frontend counts reds from this response, never re-scores locally).
     */
    @GetMapping("/compat-diff-with")
    @RequirePermission("perm:cluster:manifest:view")
    public Response<List<ExecutorCompatView>> compatDiffWith(
            @RequestParam(value = "min", required = false) String min,
            @RequestParam(value = "max", required = false) String max) {
        return Response.builderSuccess(coreReleaseManifestClient.compatDiffAgainst(min, max));
    }

    
    @GetMapping("/platform-version")
    @RequirePermission("perm:cluster:manifest:view")
    public Response<PlatformVersionView> platformVersion() {
        return Response.builderSuccess(coreReleaseManifestClient.platformVersion());
    }
}
