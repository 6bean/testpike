package com.sixbean.testpike.core.batch.api.dto;

import java.util.Map;

/**
 * Batch-level mobile performance rollup (GET /batches/{id}/report → mobilePerf).
 * <p>Deserialized straight from the {@code tp_batch_report.mobile_perf_json} snapshot
 * column: the field names here are the snapshot keys, so forwarding adds no
 * transformation and no second calculation site.
 * <p>Null rollup on the report view means the batch reported no mobile metric at
 * all; an empty metrics map is never produced for that case.
 *
 * @param metrics     metric name to its rollup (insertion order = the order the
 *                    finalize rollup stored)
 * @param prevBatchId previous-cycle batch the comparison fields were taken from
 *                    (nearest earlier finalized batch sharing project and
 *                    environment; null = first batch of the cycle)
 * @param sampleCount total mobile samples behind the reported per-task medians
 *                    (null = no task carried the sample counter)
 */
public record MobilePerfRollupView(Map<String, MobilePerfMetricView> metrics,
                                   Long prevBatchId,
                                   Long sampleCount) {
}
