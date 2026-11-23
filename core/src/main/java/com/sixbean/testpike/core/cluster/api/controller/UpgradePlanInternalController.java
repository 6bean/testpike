package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanCreateInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanImpactView;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.core.cluster.domain.service.UpgradePlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/cluster/upgrade-plans")
public class UpgradePlanInternalController {

    private final UpgradePlanService upgradePlanService;

    public UpgradePlanInternalController(UpgradePlanService upgradePlanService) {
        this.upgradePlanService = upgradePlanService;
    }

    
    @PostMapping
    public Response<UpgradePlanInfo> create(@RequestBody UpgradePlanCreateInput input) {
        return Response.builderSuccess(upgradePlanService.create(input));
    }

    
    @GetMapping
    public Response<List<UpgradePlanInfo>> list() {
        return Response.builderSuccess(upgradePlanService.listAll());
    }

    
    @PostMapping("/impact")
    public Response<UpgradePlanImpactView> impact(@RequestBody UpgradePlanCreateInput input) {
        return Response.builderSuccess(upgradePlanService.impact(input));
    }

    
    @GetMapping("/{id}")
    public Response<UpgradePlanInfo> get(@PathVariable long id) {
        return Response.builderSuccess(upgradePlanService.get(id));
    }

    
    @GetMapping("/{id}/steps")
    public Response<List<UpgradePlanStepView>> steps(@PathVariable long id) {
        return Response.builderSuccess(upgradePlanService.steps(id));
    }

    
    @PostMapping("/{id}/start")
    public Response<UpgradePlanInfo> start(@PathVariable long id) {
        return Response.builderSuccess(upgradePlanService.start(id));
    }

    /** Second-person review confirmation (reviewer is the login-context username bridged
     * from the admin surface — the identity never comes from the request body). */
    @PostMapping("/{id}/review")
    public Response<UpgradePlanInfo> review(@PathVariable long id,
                                            @RequestParam String reviewer) {
        return Response.builderSuccess(upgradePlanService.confirmDualReview(id, reviewer));
    }

    
    @PostMapping("/{id}/pause")
    public Response<UpgradePlanInfo> pause(@PathVariable long id) {
        return Response.builderSuccess(upgradePlanService.pause(id));
    }

    
    @PostMapping("/{id}/resume")
    public Response<UpgradePlanInfo> resume(@PathVariable long id) {
        return Response.builderSuccess(upgradePlanService.resume(id));
    }

    
    @PostMapping("/{id}/abort")
    public Response<UpgradePlanInfo> abort(@PathVariable long id) {
        return Response.builderSuccess(upgradePlanService.abort(id));
    }
}
