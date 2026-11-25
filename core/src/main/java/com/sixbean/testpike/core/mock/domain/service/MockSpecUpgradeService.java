package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.domain.component.SpecUpgradePlan;

/**
 * Contract-upgrade difference surface for rules imported from an API contract.
 * <p>When a contract gains a new version, the rules a mock space imported from
 * that contract family drift: endpoints appear, change shape or disappear.
 * Planning turns that drift into a deterministic three-state list (zero LLM
 * tokens — the same structure-hash diff the upload badge uses) and marking
 * disappears into a stale flag instead of a deletion, so traffic history and
 * the hit ledger stay intact.
 */
public interface MockSpecUpgradeService {

    /** Three-state upgrade plan for one space against one contract version. */
    SpecUpgradePlan planDiff(long spaceId, long specId);

    /**
     * Disable and flag the rules whose endpoint disappeared in this contract
     * version. Rows already flagged are untouched, so replaying files zero
     * changes. The hit ledger is never written by this call.
     *
     * @return number of rows newly marked
     */
    int markRemovedStale(long spaceId, long specId);
}
