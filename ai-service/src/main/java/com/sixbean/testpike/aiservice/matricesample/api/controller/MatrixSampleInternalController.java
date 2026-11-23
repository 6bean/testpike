package com.sixbean.testpike.aiservice.matricesample.api.controller;

import com.sixbean.testpike.aiservice.matricesample.api.dto.MatrixSampleRequest;
import com.sixbean.testpike.aiservice.matricesample.domain.service.MatrixSampleService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/internal/appauto")
public class MatrixSampleInternalController {

    private final MatrixSampleService matrixSampleService;

    public MatrixSampleInternalController(MatrixSampleService matrixSampleService) {
        this.matrixSampleService = matrixSampleService;
    }

    
    @PostMapping("/matrix-sample")
    public Response<Map<String, Object>> sample(@RequestBody MatrixSampleRequest request) {
        return Response.builderSuccess(matrixSampleService.sample(request));
    }
}
