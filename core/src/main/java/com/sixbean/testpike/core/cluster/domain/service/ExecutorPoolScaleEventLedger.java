package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleEvent;

import java.util.List;

/**
 * Read side of the executor-pool autoscale event ledger.
 *
 * <p>The scale-event table is written by the apply channel whenever a scale action is
 * actually issued to the fleet; this contract only exposes what the decision inputs
 * need: the epoch seconds of the newest event per pool, feeding the cooldown guardrail
 * of the scale rules. A pool that has never scaled reports 0, which the guardrail reads
 * as "not recently scaled" — the honest degradation instead of suppressing decisions on
 * a missing history.
 */
public interface ExecutorPoolScaleEventLedger {

    /**
     * Epoch seconds of the newest scale event for the pool, or 0 when the pool has no
     * recorded scale history.
     */
    long latestScaleEpochSeconds(String poolKey);

    /**
     * Newest-first window of recorded scale events for the timeline. A null or blank
     * poolKey spans the whole fleet. The limit is clamped inside the ledger so a single
     * read never streams the unbounded append-only table; an empty ledger answers an
     * empty list, never null (the apply channel has not issued scale actions yet —
     * the window reads honestly empty instead of fabricating rows).
     */
    List<ExecutorPoolScaleEvent> recentEvents(String poolKey, int limit);
}
