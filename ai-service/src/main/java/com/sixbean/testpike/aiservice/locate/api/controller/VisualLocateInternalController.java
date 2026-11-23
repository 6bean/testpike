package com.sixbean.testpike.aiservice.locate.api.controller;

import com.sixbean.testpike.aiservice.locate.api.dto.VisualLocateRequest;
import com.sixbean.testpike.aiservice.locate.domain.service.VisualLocateService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/internal/appauto")
public class VisualLocateInternalController {

    private final VisualLocateService visualLocateService;

    public VisualLocateInternalController(VisualLocateService visualLocateService) {
        this.visualLocateService = visualLocateService;
    }

    
    @PostMapping("/visual-locate")
    public Response<Map<String, Object>> locate(@RequestBody VisualLocateRequest request) {
        return Response.builderSuccess(visualLocateService.locate(request));
    }
}
