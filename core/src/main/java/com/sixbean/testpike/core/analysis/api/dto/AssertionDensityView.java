package com.sixbean.testpike.core.analysis.api.dto;

import java.util.List;

/**
 * Assertion-density summary over the latest version of every active case
 * .
 * <p>Active-case scope follows the coverage matrix single source
 * (deleted = 0 AND status &lt;&gt; 9); step rows join on the case's current
 * version so archived step versions never inflate the density.
 *
 * @param projectId project under analysis
 * @param endType end-type filter applied (null = all ends)
 * @param caseCount active cases in scope
 * @param totalSteps steps of the latest case versions
 * @param assertionSteps steps flagged as assertions
 * @param density assertionSteps / totalSteps, 0 when no steps
 * @param zeroAssertCases active cases whose latest version has no assertion step
 * @param perCase per-case breakdown, worst first (non-assertion heavy)
 */
public record AssertionDensityView(long projectId, String endType,
                                   long caseCount, long totalSteps, long assertionSteps,
                                   double density, long zeroAssertCases,
                                   List<CaseAssertionRow> perCase) {
}
