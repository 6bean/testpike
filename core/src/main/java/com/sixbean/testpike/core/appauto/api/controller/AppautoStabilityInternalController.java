package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStabilityView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoStabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoStabilityInternalController {

    private final AppautoStabilityService stabilityService;

    public AppautoStabilityInternalController(AppautoStabilityService stabilityService) {
        this.stabilityService = stabilityService;
    }

    
    @GetMapping("/stability-rows")
    public Response<List<AppautoStabilityView>> stabilityRows(
            @RequestParam long projectId,
            @RequestParam long caseId) {
        return Response.builderSuccess(stabilityService.stabilityRows(projectId, caseId));
    }
}
