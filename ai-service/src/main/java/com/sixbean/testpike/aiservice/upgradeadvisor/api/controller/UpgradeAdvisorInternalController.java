package com.sixbean.testpike.aiservice.upgradeadvisor.api.controller;

import com.sixbean.testpike.aiservice.upgradeadvisor.api.dto.UpgradeAdvisorRequest;
import com.sixbean.testpike.aiservice.upgradeadvisor.domain.service.UpgradeAdvisorService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/internal/appauto")
public class UpgradeAdvisorInternalController {

    private final UpgradeAdvisorService upgradeAdvisorService;

    public UpgradeAdvisorInternalController(UpgradeAdvisorService upgradeAdvisorService) {
        this.upgradeAdvisorService = upgradeAdvisorService;
    }

    
    @PostMapping("/upgrade-advisor")
    public Response<Map<String, Object>> advise(@RequestBody UpgradeAdvisorRequest request) {
        return Response.builderSuccess(upgradeAdvisorService.advise(request));
    }
}
