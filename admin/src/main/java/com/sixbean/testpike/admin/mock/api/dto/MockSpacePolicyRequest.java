package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Space miss-policy intake : the rung name the operator picked
 * plus the optimistic-lock version of the row they are looking at.
 * <p>Which rung names exist (passthrough / reject / llm-fallback) is decided by
 * the shared MockMissPolicies spectrum inside the core policy door — the bridge
 * forwards and never re-decides (save-chain single point, red line 6).
 *
 * @param missPolicy passthrough | reject | llm-fallback
 * @param expectedVersion optimistic-lock version from the row the operator is
 * looking at (UI passes the listed row's version through)
 */
public record MockSpacePolicyRequest(String missPolicy, Integer expectedVersion) {
}
