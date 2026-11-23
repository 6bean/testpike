package com.sixbean.testpike.mockserver.api.controller;

import com.sixbean.testpike.mockserver.api.dto.MockLoadStateView;
import com.sixbean.testpike.mockserver.domain.component.MockHitRecorder;
import com.sixbean.testpike.mockserver.domain.component.RuleLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management-plane self-check endpoint of the traffic plane (AG design D-b:
 * staleness and ledger degradation must be reportable, not just logged).
 * Sits on the management connector (18085) next to actuator health; it exposes
 * counters only — no request payloads — so it is safe to poll from ops tooling.
 * Thin by design: both components own their state, this endpoint only projects
 * them into one screen-shaped view.
 */
@RestController
@RequestMapping("/load-state")
public class MockLoadStateController {

    private final RuleLoader ruleLoader;
    private final MockHitRecorder hitRecorder;

    public MockLoadStateController(RuleLoader ruleLoader, MockHitRecorder hitRecorder) {
        this.ruleLoader = ruleLoader;
        this.hitRecorder = hitRecorder;
    }

    /** Both honest faces: rule pin status plus ledger buffer status. */
    @GetMapping
    public MockLoadStateView loadState() {
        return new MockLoadStateView(
                ruleLoader.everLoaded(),
                ruleLoader.pinnedSpaceKey(),
                ruleLoader.pinnedVersion(),
                ruleLoader.lastSuccessAt(),
                ruleLoader.isStale(),
                ruleLoader.consecutiveFailures(),
                ruleLoader.lastError(),
                hitRecorder.buffered(),
                hitRecorder.degraded(),
                hitRecorder.overflowDrops());
    }
}
