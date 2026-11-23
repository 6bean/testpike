package com.sixbean.testpike.common.mock;

import java.util.List;
import java.util.Map;

/**
 * Outcome of one kernel pass : the winning stub plus the full trace of
 * every rule evaluated before it. pathVars carries the template captures of
 * the winner so the response template can echo them back.
 *
 * @param matched whether some stub fully matched
 * @param stubId winner identity (-1 when unmatched)
 * @param stubName winner label (null when unmatched)
 * @param pathVars {var} captures from the winner's template path
 * @param traces per-rule adjudication lines in evaluation order
 */
public record MockMatchVerdict(
        boolean matched,
        long stubId,
        String stubName,
        Map<String, String> pathVars,
        List<MockMatchTrace> traces) {

    static MockMatchVerdict noMatch(List<MockMatchTrace> traces) {
        return new MockMatchVerdict(false, -1, null, Map.of(), traces);
    }

    static MockMatchVerdict hit(MockStubRule rule, Map<String, String> pathVars,
                                List<MockMatchTrace> traces) {
        return new MockMatchVerdict(true, rule.stubId(), rule.name(), pathVars, traces);
    }
}
