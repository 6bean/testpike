package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreClusterCommandClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Fleet command ledger read port (management-plane projection of the command rows the
 * protocol plane already produces; the lifecycle audit rows behind them ride the
 * existing audit chain, this bridge adds no second verdict — red line 6, R40).
 * <p>Permission reuses perm:project:executor-pool (same plane as the executor fleet
 * page, zero new seed). Status filtering, ordering and windowing stay in core; the
 * receipt and progress sentences cross unchanged so the UI shows exactly what the
 * protocol plane recorded.
 */
@RestController
@RequestMapping("/api/v1/cluster/commands")
public class ClusterCommandController {

    private final CoreClusterCommandClient coreClusterCommandClient;

    public ClusterCommandController(CoreClusterCommandClient coreClusterCommandClient) {
        this.coreClusterCommandClient = coreClusterCommandClient;
    }

    /** Command list; absent targetExecutorId spans the whole fleet as core defines it. */
    @GetMapping
    @RequirePermission("perm:project:executor-pool")
    public Response<List<ClusterCommandView>> list(
            @RequestParam(name = "targetExecutorId", required = false) Long targetExecutorId) {
        return Response.builderSuccess(coreClusterCommandClient.list(targetExecutorId));
    }
}
