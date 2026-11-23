package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;

import java.util.List;

/**
 * Demand-side input spectrum for the executor-pool autoscaler (T294c).
 * <p>One snapshot line per executor pool (end-type of online executors), carrying the
 * pending dispatch depth this pool could actually claim, its current replica line, the
 * number of executors handling work, the p95 wait of the claimable backlog and the epoch
 * of the last scale event. The decision rules consume these lines together with the
 * hot-tunable guardrail settings; nothing here decides anything itself.
 */
public interface ExecutorPoolDemandService {

    /**
     * Fresh demand snapshot for every pool backed by at least one online executor.
     *
     * @return one {@link ExecutorPoolDemand} per pool type, never null
     */
    List<ExecutorPoolDemand> snapshotPools();
}
