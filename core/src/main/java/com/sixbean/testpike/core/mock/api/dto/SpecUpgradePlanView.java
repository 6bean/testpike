package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Wire shape of the contract-upgrade difference surface (MJ P6): the whole
 * three-state list in one frame so the upgrade wizard renders without a
 * second round-trip.
 * <p>Added rows arrive advice-joined (the same single advice source the
 * upload wizard uses — default check, one-sentence reason, conflict reading);
 * changed/removed rows name the exact rule (id + verb + path) because their
 * actions are updates and stale marks against existing rows, not drafts.
 *
 * @param added       advice-joined import-ready drafts for new endpoints
 * @param changed     existing rules whose served body moved (refresh, path kept)
 * @param removed     existing rules whose endpoint left the contract
 * @param hasPrevious false for a first-version contract: nothing to upgrade
 */
public record SpecUpgradePlanView(
        List<MockStubDraftAdvice> added,
        List<SpecUpgradeRow> changed,
        List<SpecUpgradeRow> removed,
        boolean hasPrevious) {
}
