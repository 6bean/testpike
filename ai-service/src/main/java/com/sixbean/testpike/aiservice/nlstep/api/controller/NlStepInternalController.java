package com.sixbean.testpike.aiservice.nlstep.api.controller;

import com.sixbean.testpike.aiservice.nlstep.api.dto.NlStepRequest;
import com.sixbean.testpike.aiservice.nlstep.domain.service.NlStepService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/internal/appauto")
public class NlStepInternalController {

    private final NlStepService nlStepService;

    public NlStepInternalController(NlStepService nlStepService) {
        this.nlStepService = nlStepService;
    }

    
    @PostMapping("/nl-step")
    public Response<Map<String, Object>> generate(@RequestBody NlStepRequest request) {
        return Response.builderSuccess(nlStepService.generate(request));
    }
}
