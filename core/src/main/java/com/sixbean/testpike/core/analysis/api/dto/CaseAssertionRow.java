package com.sixbean.testpike.core.analysis.api.dto;

/**
 * One case's assertion profile inside {@link AssertionDensityView}.
 *
 * @param caseId        case id
 * @param title         case title (plain text for conversation rendering)
 * @param version       version counted (the case's current version)
 * @param steps         step count of that version
 * @param assertionSteps assertion step count of that version
 */
public record CaseAssertionRow(long caseId, String title, int version,
                               long steps, long assertionSteps) {
}
