package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoMatrixSampleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoMatrixSampleInternalController {

    private final AppautoMatrixSampleService matrixSampleService;

    public AppautoMatrixSampleInternalController(AppautoMatrixSampleService matrixSampleService) {
        this.matrixSampleService = matrixSampleService;
    }

    
    @PostMapping("/matrix-sample")
    public Response<AppautoMatrixSampleView> matrixSample(
            @RequestBody AppautoMatrixSampleRequest request) {
        return Response.builderSuccess(matrixSampleService.recommend(request));
    }
}
