package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.batch.api.dto.BatchInsightUpsertInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInsightView;

/**
 * Batch failure-insight landing service (AO-7 proactive insights, stage 2).
 * <p>The clustering computation lives in ai-service; only the result lands here
 * through the core internal endpoint — validation is single-point in core
 * (unknown batch rejected), the project id is server-derived from the batch row
 * (H1 isolation), and the insight-card notification fans out to the same
 * recipient family as the batch-finished notice (test owner, fallback project
 * owners).
 */
public interface BatchInsightService {

    /**
     * Store (or refresh) the insight snapshot of one finalized batch and raise the
     * AI_INSIGHT_CLUSTER station letter once per recipient per batch.
     *
     * @param batchId batch the clustering result belongs to (must exist)
     * @param input   cluster groups + counts + plain-word summary from ai-service
     */
    void upsert(long batchId, BatchInsightUpsertInput input);

    /**
     * Read the landed insight snapshot of one batch for the report-page card
     * (AO-7 stage 3 read path). Honest absence semantics: a batch without a
     * landed insight (all-pass batch, insight gate off, or clustering still
     * pending) and an unknown batch id both answer {@code null} — the caller
     * hides the card instead of surfacing an error.
     *
     * @param batchId batch to read
     * @return the stored snapshot view, or null when nothing has landed
     */
    BatchInsightView findByBatch(long batchId);
}
