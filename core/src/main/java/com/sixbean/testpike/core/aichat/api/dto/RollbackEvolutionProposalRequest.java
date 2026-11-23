package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Body of the internal rollback door (AF-i phase 3): the human who owns the
 * evolution session is pinned by the admin bridge from the login context and
 * travels here — the path alone cannot say who is clicking, and the ownership
 * guard needs that name. Missing operator fails closed.
 *
 * @param operatorId the logged-in human requesting the rollback
 */
public record RollbackEvolutionProposalRequest(Long operatorId) {
}
