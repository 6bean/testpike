package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only internal interface for the component version ledger in the core service layer, called by the admin cluster version matrix bridge.
 * <p>The permission key is validated on the admin side (reusing perm:project:executor-pool), and the tri-color decision has a single source.
 * The service decision matrix is that source; this controller is a thin wrapper only and prevents duplicate decision (ClusterEnrollInternalController).
 * This follows the same-domain pattern, and AppautoCatalogInternalController uses the same approach.
 */
@RestController
@RequestMapping("/internal/cluster")
public class ClusterComponentInternalController {

    private final ClusterComponentService componentService;

    public ClusterComponentInternalController(ClusterComponentService componentService) {
        this.componentService = componentService;
    }

    /** Cluster-wide component version ledger (version × component matrix data plane; ten fields exposed as-is). */
    @GetMapping("/components")
    public Response<List<ClusterComponentInfo>> list() {
        return Response.builderSuccess(componentService.listAll());
    }
}
