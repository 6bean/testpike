package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoNlStepService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoNlStepInternalController {

    private final AppautoNlStepService nlStepService;

    public AppautoNlStepInternalController(AppautoNlStepService nlStepService) {
        this.nlStepService = nlStepService;
    }

    
    @PostMapping("/nl-step")
    public Response<AppautoNlStepView> generate(@RequestBody AppautoNlStepRequest request) {
        return Response.builderSuccess(nlStepService.generate(request));
    }
}
