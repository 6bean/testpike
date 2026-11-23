package com.sixbean.testpike.admin.batch.api.dto;

import java.util.Map;

/**
 * Batch-level mobile performance rollup (admin mirror of the core record, 02
 * GET /batches/{id}/report response field mobilePerf).
 * <p>Field names match the {@code tp_batch_report.mobile_perf_json} snapshot keys
 * so the value passes through the bridge without transformation; recomputation on
 * the bridge is banned (the rollup has exactly one calculation site, core
 * BatchReportSupport at finalization).
 *
 * @param metrics     metric name to its rollup
 * @param prevBatchId previous-cycle batch the comparison came from, null for the
 *                    first batch of a cycle
 * @param sampleCount mobile samples behind the per-task medians, null when no task
 *                    carried the counter
 */
public record MobilePerfRollupView(Map<String, MobilePerfMetricView> metrics,
                                   Long prevBatchId,
                                   Long sampleCount) {
}
