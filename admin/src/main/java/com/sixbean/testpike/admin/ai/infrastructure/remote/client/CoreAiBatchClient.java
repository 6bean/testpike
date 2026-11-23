package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sixbean.testpike.admin.ai.api.dto.AiBatchEstimateView;
import com.sixbean.testpike.admin.ai.api.dto.AiBatchView;
import com.sixbean.testpike.common.aibatch.AiBatchItem;

import java.util.List;
import java.util.Map;

/**
 * <p>BA batch : the wire form switched to the structured item list; the plain-text legacy
 * conversion happens once on the request DTO (normalizedItems), never duplicated per call site.
 *
 */
public interface CoreAiBatchClient {

    
    default AiBatchEstimateView estimateBatch(long projectId, List<AiBatchItem> items) {
        return estimateBatch(projectId, items, null);
    }

 
    AiBatchEstimateView estimateBatch(long projectId, List<AiBatchItem> items, Long previewBatchId);

 
    /**
     * Batch door defaults: live values of the row cap (ai.batch.max-count) and
     * the default full-enumeration switch (ai.gen.batch.auto-enumerate.enabled,
     * 1=on 0=off). Missing or unreadable halves fall back to shipped defaults
     * (500 rows / on) so an older core never disables the page.
     */
    Map<String, Integer> batchLimit();

    
    Map<String, Long> createBatch(long projectId, List<AiBatchItem> items, long operatorId, boolean windowFeed);

    
    AiBatchView getBatch(long batchId);

    /**
 * Latest batch of the project, any state ( recovery batch; null when
 * none exists). Read-only — the recovery door that lets the AI generation
 * page re-attach the queue after the user leaves and comes back.
 */
    AiBatchView latestBatch(long projectId);
}
