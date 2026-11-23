package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolWatermarkPoint;

import java.util.List;

/**
 * History ledger behind the pool water-level curve (T294d data plane).
 * <p>Write side: one batch of demand snapshots per autoscaler cycle. Read side: a bounded
 * newest-first window per pool (or the whole fleet when no filter is given). The window
 * clamp lives here — read ports and the bridge forward without re-deciding (R40).
 * <p>Lifecycle side: {@link #purgeExpired()} keeps the append-only table inside the
 * operator-tunable retention bound, the paired recycling path for every sampled row (R57).
 */
public interface ExecutorPoolWatermarkLedger {

    /** Append one sample row per demand line; an empty snapshot writes nothing. */
    void appendAll(List<ExecutorPoolDemand> demands);

    /** Newest-first window; {@code executorType} null/blank spans every pool. */
    List<ExecutorPoolWatermarkPoint> recentSeries(String executorType, int limit);

    /** Delete samples older than the retention bound; returns the purged row count. */
    int purgeExpired();
}
