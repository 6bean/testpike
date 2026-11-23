package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoSupportView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeFlakyLinkView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeWindowView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoSupportService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoUpgradeAdvisorService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoUpgradeFlakyLinkService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoUpgradeWindowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoSupportInternalController {

    private final AppautoSupportService supportService;
    private final AppautoUpgradeAdvisorService upgradeAdvisorService;
    private final AppautoUpgradeWindowService upgradeWindowService;
    private final AppautoUpgradeFlakyLinkService upgradeFlakyLinkService;

    public AppautoSupportInternalController(AppautoSupportService supportService,
                                            AppautoUpgradeAdvisorService upgradeAdvisorService,
                                            AppautoUpgradeWindowService upgradeWindowService,
                                            AppautoUpgradeFlakyLinkService upgradeFlakyLinkService) {
        this.supportService = supportService;
        this.upgradeAdvisorService = upgradeAdvisorService;
        this.upgradeWindowService = upgradeWindowService;
        this.upgradeFlakyLinkService = upgradeFlakyLinkService;
    }

    
    @GetMapping("/engine-support")
    public Response<List<AppautoSupportView>> engineSupport(@RequestParam long projectId) {
        return Response.builderSuccess(supportService.support(projectId));
    }

    
    @PostMapping("/upgrade-advice")
    public Response<AppautoUpgradeAdviceView> upgradeAdvice(
            @RequestBody AppautoUpgradeAdviceRequest request) {
        return Response.builderSuccess(upgradeAdvisorService.advise(request));
    }

    
    @GetMapping("/upgrade-window")
    public Response<List<AppautoUpgradeWindowView>> upgradeWindow() {
        return Response.builderSuccess(upgradeWindowService.recommend());
    }

    
    @GetMapping("/upgrade-flaky-links")
    public Response<List<AppautoUpgradeFlakyLinkView>> upgradeFlakyLinks() {
        return Response.builderSuccess(upgradeFlakyLinkService.correlate());
    }
}
