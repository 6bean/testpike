package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolScaleEventView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolScalePlanView;
import com.sixbean.testpike.admin.cluster.api.dto.ExecutorPoolWatermarkView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreExecutorPoolScaleClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Fleet autoscaler overview read port (executor-pool data plane of the fleet page).
 * <p>Permission reuses perm:project:executor-pool (same plane as the executor fleet
 * page, no new seed); pools are a global asset, not per-project. All decision
 * semantics — depth dimensions, P95 pressure, min/max clamps, cooldown, budget guard,
 * drain-first — live in the core rules source; this bridge forwards the plan and the
 * user-facing reason sentence unchanged (red line 6, no second verdict on this side).
 */
@RestController
@RequestMapping("/api/v1/cluster/pools")
public class ExecutorPoolFleetController {

    private final CoreExecutorPoolScaleClient coreExecutorPoolScaleClient;

    public ExecutorPoolFleetController(CoreExecutorPoolScaleClient coreExecutorPoolScaleClient) {
        this.coreExecutorPoolScaleClient = coreExecutorPoolScaleClient;
    }

    /** Per-pool demand + verdict snapshot; absent utilization means no budget evidence. */
    @GetMapping("/scale-plan")
    @RequirePermission("perm:project:executor-pool")
    public Response<List<ExecutorPoolScalePlanView>> scalePlan(
            @RequestParam(name = "budgetUtilization", required = false) Double budgetUtilization) {
        return Response.builderSuccess(
                coreExecutorPoolScaleClient.scalePlan(budgetUtilization == null ? 0.0 : budgetUtilization));
    }

    /**
     * Newest-first autoscale event window for the fleet timeline. Filters pass through
     * verbatim — pool clamp and default window live in the core ledger source, the
     * reason sentence is never restated here (no second verdict on this side).
     */
    @GetMapping("/scale-events")
    @RequirePermission("perm:project:executor-pool")
    public Response<List<ExecutorPoolScaleEventView>> scaleEvents(
            @RequestParam(name = "poolKey", required = false) String poolKey,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return Response.builderSuccess(coreExecutorPoolScaleClient.scaleEvents(poolKey, limit));
    }

    /**
     * Newest-first pool-watermark series for the fleet curve band. Filters pass through
     * verbatim — the type filter, default window and clamp all live in the core ledger
     * source; samples are raw observations, admin computes no trend or verdict here.
     */
    @GetMapping("/watermarks")
    @RequirePermission("perm:project:executor-pool")
    public Response<List<ExecutorPoolWatermarkView>> watermarks(
            @RequestParam(name = "executorType", required = false) String executorType,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return Response.builderSuccess(coreExecutorPoolScaleClient.watermarks(executorType, limit));
    }
}
