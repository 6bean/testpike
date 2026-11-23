package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.cluster.api.dto.PlatformVersionView;
import com.sixbean.testpike.core.cluster.domain.service.PlatformVersionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal platform self-version interface in the core service layer, called by the admin upgrade-center bridge.
 * <p>Permission key is validated on the admin side (read=perm:cluster:manifest:view, with no new key introduced).
 * This controller is thin and only wraps, with no business semantics (same-domain style as ReleaseManifestInternalController).
 * The verdict has a single source: the VersionCompare spectrum in PlatformVersionService; the bridge and frontend must not make secondary decisions.
 */
@RestController
@RequestMapping("/internal/cluster/platform-version")
public class PlatformVersionInternalController {

    private final PlatformVersionService platformVersionService;

    public PlatformVersionInternalController(PlatformVersionService platformVersionService) {
        this.platformVersionService = platformVersionService;
    }

    /** Comparison view of the running version and the latest release manifest (missing values on either side are honestly null/UNKNOWN; the five verdict spectra are described in the DTO note). */
    @GetMapping
    public Response<PlatformVersionView> platformVersion() {
        return Response.builderSuccess(platformVersionService.platformVersion());
    }
}
