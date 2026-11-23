package com.sixbean.testpike.core.aichat.domain.component;

import java.util.List;
import java.util.Map;

/**
 * Planned queue action for the pending draft cards of one target after a
 * sibling card was confirmed by the human (see {@link DraftQueueRenewalPolicy}).
 * <p>Every collection is keyed/ordered by draft id; ids that already sit on
 * the current version appear nowhere — renewal is idempotent and plans zero
 * writes when nothing moved.
 *
 * @param renewAnchors          draft id -> the new base_version the card is
 *                              re-anchored onto (disjoint proposal survives)
 * @param staleIds              ids turning into the one-click-regenerate
 *                              stale state, at most one, newest by issue order
 * @param voidedByExclusiveGate older stale ids auto-voided to keep the stale
 *                              state exclusive per target, with the human-readable
 *                              reason naming the surviving card
 */
public record RenewalPlan(Map<Long, Integer> renewAnchors,
                          List<Long> staleIds,
                          Map<Long, String> voidedByExclusiveGate) {

    public RenewalPlan {
        renewAnchors = Map.copyOf(renewAnchors);
        staleIds = List.copyOf(staleIds);
        voidedByExclusiveGate = Map.copyOf(voidedByExclusiveGate);
    }
}
