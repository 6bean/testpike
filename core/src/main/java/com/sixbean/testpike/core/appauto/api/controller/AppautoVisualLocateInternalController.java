package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoVisualLocateRequest;
import com.sixbean.testpike.core.appauto.domain.service.AppautoVisualLocateService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoVisualLocateInternalController {

    private final AppautoVisualLocateService visualLocateService;

    public AppautoVisualLocateInternalController(AppautoVisualLocateService visualLocateService) {
        this.visualLocateService = visualLocateService;
    }

    
    @PostMapping("/visual-locate")
    public Response<Map<String, Object>> locate(
            @RequestBody AppautoVisualLocateRequest request) {
        return Response.builderSuccess(visualLocateService.locate(request));
    }
}
