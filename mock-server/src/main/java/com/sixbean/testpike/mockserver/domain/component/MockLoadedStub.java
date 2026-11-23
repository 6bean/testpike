package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.mock.MockStubRule;

/**
 * One snapshot rule paired with what it serves: the kernel-facing
 * {@link MockStubRule} plus the raw response builder payload kept aside for
 * the renderer. The pairing never leaks transport types into the kernel and
 * never resolves the payload early — rendering happens per request so echo
 * and cycle placeholders stay live.
 * <p>The three scenario columns ride along as plain labels:
 * membership name, the state the rule fires on, the state it advances to.
 * Filtering against the machine happens in the traffic controller's candidate
 * pre-filter, not inside the matching kernel (single-dialect red line).
 *
 * @param rule reduced rule the matching kernel adjudicates
 * @param responseJson response builder payload (JSON object text)
 * @param scenarioName stateful scenario membership (null = stateless rule)
 * @param requiredState scenario state this rule fires on (null/blank = any state)
 * @param newState scenario state this rule advances to (null = no advance)
 */
public record MockLoadedStub(
        MockStubRule rule,
        String responseJson,
        String scenarioName,
        String requiredState,
        String newState) {

    /** Two-arg form kept for stateless rows (tests, executor-side reuse). */
    public MockLoadedStub(MockStubRule rule, String responseJson) {
        this(rule, responseJson, null, null, null);
    }
}
