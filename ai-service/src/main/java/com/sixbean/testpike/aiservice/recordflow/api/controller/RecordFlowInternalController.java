package com.sixbean.testpike.aiservice.recordflow.api.controller;

import com.sixbean.testpike.aiservice.recordflow.api.dto.RecordFlowRequest;
import com.sixbean.testpike.aiservice.recordflow.domain.service.RecordFlowService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/internal/appauto")
public class RecordFlowInternalController {

    private final RecordFlowService recordFlowService;

    public RecordFlowInternalController(RecordFlowService recordFlowService) {
        this.recordFlowService = recordFlowService;
    }

    
    @PostMapping("/record-flow")
    public Response<Map<String, Object>> generate(@RequestBody RecordFlowRequest request) {
        return Response.builderSuccess(recordFlowService.generate(request));
    }
}
