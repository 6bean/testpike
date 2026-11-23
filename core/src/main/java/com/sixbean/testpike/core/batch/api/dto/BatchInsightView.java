package com.sixbean.testpike.core.batch.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Batch failure-insight read view (AO-7 proactive insights, stage 3 read path).
 * <p>Straight read of the tp_batch_insight ledger row — the report-page insight
 * card consumes this shape as stored (zero recomputation on the read path, same
 * discipline as the batch report snapshot door). A batch without a landed
 * insight answers {@code null} from the service (honest absence: the card is
 * hidden, never an error toast for the normal all-pass / gate-off case).
 *
 * @param batchId           owning batch
 * @param failCount         total failed tasks that took part in the clustering
 * @param unattributedCount failed tasks still without AI attribution at landing
 *                          time (grouped by executor error category; stated
 *                          honestly on the card)
 * @param clusters          ranked group list, each entry {class,label,count}
 *                          (parsed from clusters_json, never raw text)
 * @param summary           plain-word one-line summary shared with the station
 *                          letter
 */
public record BatchInsightView(
        long batchId,
        int failCount,
        int unattributedCount,
        List<Map<String, Object>> clusters,
        String summary) {
}
