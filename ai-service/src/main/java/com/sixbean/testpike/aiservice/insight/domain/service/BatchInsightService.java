package com.sixbean.testpike.aiservice.insight.domain.service;

import com.sixbean.testpike.common.mq.BatchFinalizedEventMessage;

/**
 * Batch failure root-cause clustering (AO-7 proactive insights, stage 2b).
 * <p>Consumes a batch-finalized event, groups the batch's failed tasks by attributed
 * class (AI attribution first, executor error category as the honest fallback with the
 * unattributed count kept visible), then lands the snapshot through the core internal
 * write-back endpoint — ai-service never writes business tables directly
 * (two-sides separation).
 */
public interface BatchInsightService {

    /** Cluster the failed tasks of the finalized batch and write the snapshot back to core. */
    void cluster(BatchFinalizedEventMessage message);
}
