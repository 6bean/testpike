package com.sixbean.testpike.core.batch.api.dto;

import java.math.BigDecimal;

/**
 * One mobile sampling metric inside the batch performance rollup.
 * <p>Every value is forwarded verbatim from the finalize-time snapshot column
 * {@code tp_batch_report.mobile_perf_json} — the read path never recomputes a
 * median and never derives a delta (single-source discipline: the rollup lives in
 * {@code BatchReportSupport}, the per-task readings live on the executor side).
 *
 * @param median     batch value of the metric = median across the per-task medians
 *                   that reported it, rounded to two decimals at finalize time
 * @param taskCount  number of tasks in the batch that reported this metric
 * @param prevMedian same metric in the previous-cycle batch; null when that batch
 *                   never reported it (honest absence, never zero-filled)
 * @param delta      median minus prevMedian; null whenever prevMedian is null
 */
public record MobilePerfMetricView(BigDecimal median,
                                   Integer taskCount,
                                   BigDecimal prevMedian,
                                   BigDecimal delta) {
}
