package com.sixbean.testpike.core.aichat.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same-target draft queue renewal decisions (AM batch piece 3, queue half).
 * <p>When the human confirms one draft card, every other pending card cut
 * against the same target must renew automatically instead of waiting for a
 * manual "renew" click: a card whose proposal is disjoint from the changes
 * that just landed is re-anchored onto the current row version, a card whose
 * proposal intersects (or whose baseline cannot be reconstructed) turns into
 * the one-click-regenerate stale state, and the stale state stays exclusive
 * per target — at most one actionable stale card, the newest by issue order.
 * <p>The per-card merge verdicts come from the revision-merge gate; this
 * suite pins the queue triage that consumes them. The live write path is
 * covered by the domain suites of each landing strategy and the L3 probe at
 * segment close.
 */
class DraftQueueRenewalPolicyTest {

    private final DraftQueueRenewalPolicy policy = new DraftQueueRenewalPolicy();

    @Test
    void disjointSiblingsRebaseOntoTheCurrentVersion() {
        RenewalPlan plan = policy.renewQueue(
                List.of(3L, 5L),
                Map.of(3L, 1, 5L, 1),
                Map.of(3L, RevisionMergeStatus.REBASE, 5L, RevisionMergeStatus.REBASE),
                2);
        assertEquals(Map.of(3L, 2, 5L, 2), plan.renewAnchors());
        assertTrue(plan.staleIds().isEmpty());
        assertTrue(plan.voidedByExclusiveGate().isEmpty());
    }

    @Test
    void intersectingSiblingTurnsStaleInsteadOfRebasing() {
        RenewalPlan plan = policy.renewQueue(
                List.of(5L),
                Map.of(5L, 1),
                Map.of(5L, RevisionMergeStatus.CONFLICT),
                2);
        assertTrue(plan.renewAnchors().isEmpty());
        assertEquals(List.of(5L), plan.staleIds());
    }

    @Test
    void missingBaselineRefusesConservativelyToStale() {
        RenewalPlan plan = policy.renewQueue(
                List.of(7L),
                Map.of(7L, 1),
                Map.of(7L, RevisionMergeStatus.BASELINE_MISSING),
                3);
        assertTrue(plan.renewAnchors().isEmpty());
        assertEquals(List.of(7L), plan.staleIds());
    }

    @Test
    void onlyTheNewestStaleCardSurvivesTheExclusiveGate() {
        RenewalPlan plan = policy.renewQueue(
                List.of(5L, 7L),
                Map.of(5L, 1, 7L, 1),
                Map.of(5L, RevisionMergeStatus.CONFLICT,
                        7L, RevisionMergeStatus.BASELINE_MISSING),
                4);
        assertEquals(List.of(7L), plan.staleIds());
        assertEquals(1, plan.voidedByExclusiveGate().size());
        String reason = plan.voidedByExclusiveGate().get(5L);
        assertTrue(reason.contains("7"), "refusal must name the surviving card: " + reason);
        assertFalse(reason.isEmpty());
    }

    @Test
    void siblingAlreadyOnTheCurrentVersionNeedsNoRenewalWrite() {
        RenewalPlan plan = policy.renewQueue(
                List.of(5L),
                Map.of(5L, 2),
                Map.of(5L, RevisionMergeStatus.REBASE),
                2);
        assertTrue(plan.renewAnchors().isEmpty(),
                "replay onto an unchanged anchor must plan zero writes");
        assertTrue(plan.staleIds().isEmpty());
    }

    @Test
    void emptyQueueProducesAnEmptyPlan() {
        RenewalPlan plan = policy.renewQueue(List.of(), Map.of(), Map.of(), 9);
        assertTrue(plan.renewAnchors().isEmpty());
        assertTrue(plan.staleIds().isEmpty());
        assertTrue(plan.voidedByExclusiveGate().isEmpty());
    }

    @Test
    void mixedQueueRenewsDisjointAndStalesIntersectingTogether() {
        RenewalPlan plan = policy.renewQueue(
                List.of(4L, 6L),
                Map.of(4L, 1, 6L, 1),
                Map.of(4L, RevisionMergeStatus.REBASE, 6L, RevisionMergeStatus.CONFLICT),
                3);
        assertEquals(Map.of(4L, 3), plan.renewAnchors());
        assertEquals(List.of(6L), plan.staleIds());
        assertTrue(plan.voidedByExclusiveGate().isEmpty());
    }
}
