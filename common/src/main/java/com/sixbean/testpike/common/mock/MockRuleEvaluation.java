package com.sixbean.testpike.common.mock;

import java.util.List;
import java.util.Map;

/**
 * Per-rule evaluation result inside the kernel pass : did this stub
 * fully match, which template variables it captured, and the trace lines it
 * produced. A plain top-level record — the codebase bans inner classes.
 *
 * @param ok every facet of one stub passed
 * @param pathVars {var} captures when ok, empty otherwise
 * @param traces trace lines contributed by this rule's evaluation
 */
record MockRuleEvaluation(boolean ok, Map<String, String> pathVars, List<MockMatchTrace> traces) {
}
