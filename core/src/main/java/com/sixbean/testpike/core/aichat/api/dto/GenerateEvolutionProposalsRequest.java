package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Body of a manual self-evolution proposal run (AF-i phase 2b-ii).
 *
 * @param month yyyy-MM observation window (fail-closed validated core-side)
 * @param projectId project segment the cards will land in (mandatory)
 * @param operatorId the login-pinned human who triggers the run and owns the
 * resulting evolution session — never taken from the UI
 * as a free choice (AF-g horizontal line)
 */
public record GenerateEvolutionProposalsRequest(String month,
                                                Long projectId,
                                                Long operatorId) {
}
