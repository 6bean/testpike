package com.sixbean.testpike.core.webelement.api.controller;

import com.sixbean.testpike.core.webelement.api.dto.WebElementHealthView;
import com.sixbean.testpike.core.webelement.api.dto.RevisionStormView;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.webelement.domain.service.WebElementHealthService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementStormService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read side of the step-health account and the revision-storm aggregation
 * (WE S7 observation surface). Transport only: the ledger rows come from the
 * single WebElementService segment door, the readings from the single
 * health/storm services, so the admin board never grows a second ladder
 * (R40). Literal routes here outrank the sibling controller's /{id} door —
 * same posture as the list door's literal-vs-id note.
 */
@RestController
@RequestMapping("/internal/web-elements")
public class WebElementHealthStormInternalController {

    private final WebElementService webElementService;
    private final WebElementHealthService healthService;
    private final WebElementStormService stormService;

    public WebElementHealthStormInternalController(WebElementService webElementService,
                                                   WebElementHealthService healthService,
                                                   WebElementStormService stormService) {
        this.webElementService = webElementService;
        this.healthService = healthService;
        this.stormService = stormService;
    }

    /**
 * Health reading per ledger row of the project segment (optional page-key
 * filter). Elements with no anchor outcome in any window answer the
 * honest unreached reading, never a fabricated green.
 */
    @GetMapping("/health")
    public Response<List<WebElementHealthView>> health(@RequestParam long projectId,
                                                       @RequestParam(required = false) String pageKey) {
        List<Long> ids = webElementService.listByProject(projectId,
                java.util.Optional.ofNullable(pageKey).filter(s -> !s.isBlank()).orElse(null))
                .stream().map(webElement -> webElement.id()).filter(java.util.Objects::nonNull).toList();
        java.util.Map<Long, WebElementHealthView> readings = healthService.healthFor(ids);
        return Response.builderSuccess(ids.stream()
                .map(id -> readings.getOrDefault(id, WebElementHealthView.unreached(id)))
                .toList());
    }

    /** Active revision storms of the project, one card per storming page. */
    @GetMapping("/storms")
    public Response<List<RevisionStormView>> storms(@RequestParam long projectId) {
        return Response.builderSuccess(stormService.activeStorms(projectId));
    }
}
