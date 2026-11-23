package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanCreateRequest;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanImpactView;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreUpgradePlanClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/cluster/upgrade-plans")
public class UpgradePlanController {

    private final CoreUpgradePlanClient coreUpgradePlanClient;

    public UpgradePlanController(CoreUpgradePlanClient coreUpgradePlanClient) {
        this.coreUpgradePlanClient = coreUpgradePlanClient;
    }

    
    @PostMapping
    @RequirePermission("perm:cluster:upgrade:run")
    public Response<UpgradePlanView> create(@RequestBody UpgradePlanCreateRequest request) {
        return Response.builderSuccess(coreUpgradePlanClient.create(withLoginOperator(request)));
    }

    
    @PostMapping("/impact")
    @RequirePermission("perm:cluster:upgrade:run")
    public Response<UpgradePlanImpactView> impact(@RequestBody UpgradePlanCreateRequest request) {
        return Response.builderSuccess(coreUpgradePlanClient.impact(withLoginOperator(request)));
    }

    
    @GetMapping
    @RequirePermission("perm:project:executor-pool")
    public Response<List<UpgradePlanView>> list() {
        return Response.builderSuccess(coreUpgradePlanClient.listAll());
    }

    
    @GetMapping("/{id}")
    @RequirePermission("perm:project:executor-pool")
    public Response<UpgradePlanView> get(@PathVariable long id) {
        return Response.builderSuccess(coreUpgradePlanClient.get(id));
    }

    
    @GetMapping("/{id}/steps")
    @RequirePermission("perm:project:executor-pool")
    public Response<List<UpgradePlanStepView>> steps(@PathVariable long id) {
        return Response.builderSuccess(coreUpgradePlanClient.steps(id));
    }

    
    @PostMapping("/{id}/start")
    @RequirePermission("perm:cluster:upgrade:run")
    public Response<UpgradePlanView> start(@PathVariable long id) {
        return Response.builderSuccess(coreUpgradePlanClient.start(id));
    }

    /**
     * Second-person review confirmation (the reviewer is the login-context username
     * bridged down to core — never taken from the request body; permission surface
     * equals the start surface: reviewing grants no extra power, it records that a
     * second upgrade-authorized person has seen this change).
     */
    @PostMapping("/{id}/review")
    @RequirePermission("perm:cluster:upgrade:run")
    public Response<UpgradePlanView> review(@PathVariable long id) {
        return Response.builderSuccess(coreUpgradePlanClient.review(id,
                UserContext.current().username()));
    }

    
    @PostMapping("/{id}/pause")
    @RequirePermission("perm:cluster:upgrade:run")
    public Response<UpgradePlanView> pause(@PathVariable long id) {
        return Response.builderSuccess(coreUpgradePlanClient.pause(id));
    }

    
    @PostMapping("/{id}/resume")
    @RequirePermission("perm:cluster:upgrade:run")
    public Response<UpgradePlanView> resume(@PathVariable long id) {
        return Response.builderSuccess(coreUpgradePlanClient.resume(id));
    }

    
    @PostMapping("/{id}/abort")
    @RequirePermission("perm:cluster:upgrade:run")
    public Response<UpgradePlanView> abort(@PathVariable long id) {
        return Response.builderSuccess(coreUpgradePlanClient.abort(id));
    }

    
    private static UpgradePlanCreateRequest withLoginOperator(UpgradePlanCreateRequest request) {
        return new UpgradePlanCreateRequest(request.scope(), request.engineCodes(),
                request.canaryNodeIds(), request.batchSize(), request.baselineRef(),
                request.manifestVersion(), request.productionScope(),
                request.maintenanceWindow(), UserContext.current().username());
    }
}
