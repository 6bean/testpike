package com.sixbean.testpike.core.mock.domain.component;

import java.util.List;

/**
 * Deterministic upgrade plan for the contract-difference surface: drafts for
 * endpoints the new version introduced (ready for the ordinary import chain),
 * rule ids whose endpoint changed shape (body refresh, path kept), and rule
 * ids whose endpoint vanished (stale mark + disable, never delete).
 *
 * @param addedDrafts     import-ready drafts for newly declared endpoints
 * @param changedStubIds  existing rules needing the new response body
 * @param removedStubIds  existing rules whose endpoint left the contract
 * @param hasPrevious     false when the planned version has no predecessor,
 *                        in which case every list is empty by definition
 */
public record SpecUpgradePlan(
        List<MockStubDraft> addedDrafts,
        List<Long> changedStubIds,
        List<Long> removedStubIds,
        boolean hasPrevious) {

    /** Plan of a first-version contract: nothing to upgrade, nothing to mark. */
    public static SpecUpgradePlan noPrevious() {
        return new SpecUpgradePlan(List.of(), List.of(), List.of(), false);
    }
}
