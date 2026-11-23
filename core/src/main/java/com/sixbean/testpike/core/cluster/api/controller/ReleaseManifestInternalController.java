package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorCompatView;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseManifestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal release manifest interface in the core service layer, used by the admin upgrade center bridge.
 * <p>Permission keys are validated on the admin side (register = perm:cluster:upgrade:run, read = perm:cluster:manifest:view).
 * This controller is thin and only wraps without business semantics (same-domain pattern as ClusterComponentInternalController).
 * Input validation is single-sourced in ReleaseManifestService (no secondary validation).
 */
@RestController
@RequestMapping("/internal/cluster/release-manifest")
public class ReleaseManifestInternalController {

    private final ReleaseManifestService manifestService;

    public ReleaseManifestInternalController(ReleaseManifestService manifestService) {
        this.manifestService = manifestService;
    }

    /** Register or overwrite release manifest (idempotent by unique version row; operator audit via @Audit on admin side). */
    @PostMapping("/register")
    public Response<ReleaseManifestInfo> register(@RequestBody ReleaseRegisterInput input) {
        return Response.builderSuccess(manifestService.register(input));
    }

    /** Latest release manifest (empty table = null = honest empty state for not imported). */
    @GetMapping("/latest")
    public Response<ReleaseManifestInfo> latest() {
        return Response.builderSuccess(manifestService.latest());
    }

    /** Full manifest list (version descending = upgrade center changelog consumption order). */
    @GetMapping("/list")
    public Response<List<ReleaseManifestInfo>> list() {
        return Response.builderSuccess(manifestService.listAll());
    }

    /** Compatibility health check card data plane: latest range × all executor ledger rows compute decision columns in real time (authoritative source for red highlighting). */
    @GetMapping("/compat-diff")
    public Response<List<ExecutorCompatView>> compatDiff() {
        return Response.builderSuccess(manifestService.compatDiff());
    }

    /**
     * Hypothetical-range impact pre-check for the candidate confirm drawer — scores
     * executor rows against the not-yet-published range instead of latest, so the
     * preview is exactly what confirming would enforce (verdict stays core-side,
     * single spectrum; the frontend never recomputes ranges itself).
     */
    @GetMapping("/compat-diff-with")
    public Response<List<ExecutorCompatView>> compatDiffWith(
            @RequestParam(value = "min", required = false) String min,
            @RequestParam(value = "max", required = false) String max) {
        return Response.builderSuccess(manifestService.compatDiffAgainst(min, max));
    }
}
