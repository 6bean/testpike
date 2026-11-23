package com.sixbean.testpike.core.behavior.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.behavior.api.dto.BehaviorRolloutView;
import com.sixbean.testpike.core.behavior.domain.component.BehaviorRolloutMonitor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal read endpoint exposing the D4 gray-release rollout review over the
 * outcome ledger ( item 7 wiring half; admin bridge is the only consumer,
 * core keeps its no-external-surface rule). Advisory-grade: it reports result
 * rates and a rollback suggestion; every template status change stays a human
 * action through the existing D4 endpoints.
 */
@RestController
@RequestMapping("/internal/behavior")
public class BehaviorRolloutInternalController {

    private final BehaviorRolloutMonitor monitor;

    public BehaviorRolloutInternalController(BehaviorRolloutMonitor monitor) {
        this.monitor = monitor;
    }

    /** Rollout review of one template family (NULL data = the family has no live gray release). */
    @GetMapping("/rollout-advisory")
    public Response<BehaviorRolloutView> rolloutAdvisory(@RequestParam String domain,
                                                         @RequestParam String name) {
        return Response.builderSuccess(monitor.advisoryFor(domain, name));
    }
}
