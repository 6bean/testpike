package com.sixbean.testpike.admin.batch.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Batch failure-insight card view (admin mirror, JSON-isomorphic with the core
 * BatchInsightView — AO-7 stage 3 read path, GET /api/v1/batches/{id}/insight).
 * <p>Direct read of the core landing row; the bridge never recomputes clusters.
 * data=null = honest absence (all-pass batch, insight gate off, or clustering
 * still pending) — the frontend hides the insight card.
 *
 * @param batchId           owning batch
 * @param failCount         failed tasks that took part in the clustering
 * @param unattributedCount failures without AI attribution yet (grouped by
 *                          executor error category; stated honestly on the card)
 * @param clusters          ranked groups, each entry {class,label,count}
 * @param summary           plain-word one-line summary
 */
public record BatchInsightView(
        long batchId,
        int failCount,
        int unattributedCount,
        List<Map<String, Object>> clusters,
        String summary) {
}
