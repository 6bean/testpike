package com.sixbean.testpike.admin.mock.api.dto;

import java.util.List;

/**
 * Admin mirror of core's one-frame contract-upgrade plan (MJ P6): added rows
 * arrive advice-joined from core's single advice source, changed/removed rows
 * name the exact rule. The bridge forwards it untouched — defaults, advice
 * wording and the stale-mark semantics are all core's single point.
 *
 * @param added       advice-joined import-ready drafts for new endpoints
 * @param changed     existing rules needing the refreshed served body
 * @param removed     existing rules whose endpoint left the contract
 * @param hasPrevious false for a first-version contract (nothing to upgrade)
 */
public record MockSpecUpgradePlanView(
        List<MockStubDraftAdviceView> added,
        List<MockSpecUpgradeRowView> changed,
        List<MockSpecUpgradeRowView> removed,
        boolean hasPrevious) {
}
