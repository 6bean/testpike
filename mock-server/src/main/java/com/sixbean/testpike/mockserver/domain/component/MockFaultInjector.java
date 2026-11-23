package com.sixbean.testpike.mockserver.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mock.MockFaultRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.DoubleSupplier;

/**
 * Failure-injection decision kernel (design F9, M7 . Two layers, one
 * spectrum, no second dialect:
 * <ul>
 * <li><b>stub level</b> — the response builder payload may carry a
 * {@code fault} key (type delay/disconnect/status/malformed/dribble with an
 * optional {@code probability} gate). When the key is present it owns the
 * decision entirely: a failed roll means "serve normally", it never falls
 * through to the space slider (double injection would lie about ratios).</li>
 * <li><b>space level</b> — the chaos slider (0-100 %) injects a plain 503
 * labelled {@code chaos} on requests no stub fault claimed. This is the
 * retry-storm / fault-tolerance drill knob (frontend resilience exercises).</li>
 * </ul>
 * Fail-closed discipline: an unknown fault type, an out-of-range probability,
 * an inverted delay interval, a non-5xx status override, or a malformed fault
 * on a non-JSON body all throw with human-readable words — a half-configured
 * fault must refuse loudly, never serve a pretend-fault. The save chain
 * (core MockRuleValidator) blocks the same shapes before rows land; this gate
 * is the last honest word on the data plane for rules saved before a schema
 * drift.
 * <p>Delays are clamped to {@code testpike.mock.fault.max-delay-ms} (K8, hot
 * reload via restart) so a typo of "maxMs: 3600000" cannot park servlet
 * threads. The randomness arrives as an injected {@link DoubleSupplier} so
 * every branch is testable with scripted rolls.
 */
@Component
public class MockFaultInjector {

    private final DoubleSupplier roll;
    private final int maxDelayMs;

    /** Production wiring: wire-true randomness, ceiling from the K8 key. */
    @org.springframework.beans.factory.annotation.Autowired
    public MockFaultInjector(@Value("${testpike.mock.fault.max-delay-ms:60000}") int maxDelayMs) {
        this(() -> java.util.concurrent.ThreadLocalRandom.current().nextDouble(), maxDelayMs);
    }

    /** Roll-injectable form (tests script every branch deterministically). */
    public MockFaultInjector(DoubleSupplier roll, int maxDelayMs) {
        this.roll = roll;
        this.maxDelayMs = maxDelayMs;
    }

    /**
     * Decide the fault for one served request.
     *
     * @param stubFault     the stub payload's {@code fault} node (null/missing = none)
     * @param chaosPercent  the space slider 0-100 (values outside are clamped,
     *                      never trusted as-is: a stale snapshot may predate range checks)
     * @param responseRoot  the served stub's response builder document (JSON
     *                      shape matters for the malformed fault)
     */
    public MockFaultDecision decide(JsonNode stubFault, int chaosPercent, JsonNode responseRoot) {
        if (stubFault != null && !stubFault.isNull()) {
            return decideStubFault(stubFault, responseRoot);
        }
        int percent = Math.max(0, Math.min(100, chaosPercent));
        if (percent <= 0) {
            return MockFaultDecision.none();
        }
        if (roll.getAsDouble() * 100 < percent) {
            return new MockFaultDecision(true, "chaos", 0, MockFaultRules.CHAOS_STATUS, 0, 0);
        }
        return MockFaultDecision.none();
    }

    /** Entry point for the traffic controller when the payload was never parsed
     * into a node yet: extract the fault key honestly (non-object = refused). */
    public MockFaultDecision decideFromPayload(JsonNode responseRoot, int chaosPercent) {
        JsonNode fault = responseRoot == null ? null : responseRoot.get("fault");
        return decide(fault, chaosPercent, responseRoot);
    }

    private MockFaultDecision decideStubFault(JsonNode fault, JsonNode responseRoot) {
        // One parameter-domain spectrum with the save chain (MockFaultRules):
        // the save gate blocks these shapes before rows land; this is the last
        // honest word on the data plane for rules saved before a schema drift.
        String violation = MockFaultRules.faultViolation(fault, responseRoot);
        if (violation != null) {
            throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
        }
        String type = fault.path("type").asText("");
        // Probability gate: missing key = always. Bounds were judged above;
        // here the roll alone decides whether this request draws the fault.
        if (fault.hasNonNull("probability")
                && roll.getAsDouble() * 100 >= fault.path("probability").asDouble()) {
            return MockFaultDecision.none();
        }
        // A stub fault key owns the decision entirely — whatever this branch
        // returns, the chaos slider never second-guesses it.
        return switch (type) {
            case "delay" -> {
                int min = fault.path("minMs").asInt(0);
                int max = fault.path("maxMs").asInt(min);
                int delay = min + (int) Math.round(roll.getAsDouble() * (max - min));
                // ceiling guard: a typo'd interval parks servlet threads no
                // longer than the configured bound, whatever the roll says
                delay = Math.min(Math.max(delay, 0), maxDelayMs);
                yield new MockFaultDecision(true, "delay", delay, 0, 0, 0);
            }
            case "disconnect" -> new MockFaultDecision(true, "disconnect", 0, 0, 0, 0);
            case "status" -> new MockFaultDecision(true, "status", 0,
                    fault.path("statusCode").asInt(MockFaultRules.DEFAULT_FAULT_STATUS), 0, 0);
            case "malformed" -> new MockFaultDecision(true, "malformed", 0, 0, 0, 0);
            case "dribble" -> {
                int chunkBytes = fault.path("chunkBytes").asInt(64);
                int chunkDelayMs = Math.min(fault.path("chunkDelayMs").asInt(100), maxDelayMs);
                yield new MockFaultDecision(true, "dribble", 0, 0, chunkBytes, chunkDelayMs);
            }
            default -> throw new ServiceException("故障类型「" + type + "」未接入决策分支",
                    BizErrorCode.VALIDATION_ERROR);
        };
    }
}
