package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoRecordFlowService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoRecordFlowInternalController {

    private final AppautoRecordFlowService recordFlowService;

    public AppautoRecordFlowInternalController(AppautoRecordFlowService recordFlowService) {
        this.recordFlowService = recordFlowService;
    }

    
    @PostMapping("/record-flow")
    public Response<AppautoRecordFlowView> generate(@RequestBody AppautoRecordFlowRequest request) {
        return Response.builderSuccess(recordFlowService.generate(request));
    }
}
