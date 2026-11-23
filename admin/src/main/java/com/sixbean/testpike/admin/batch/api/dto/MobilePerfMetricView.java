package com.sixbean.testpike.admin.batch.api.dto;

import java.math.BigDecimal;

/**
 * One mobile sampling metric inside the batch performance rollup (admin mirror,
 * JSON-isomorphic with the core record of the same name).
 * <p>The bridge forwards what core read from the snapshot column and performs no
 * arithmetic: median, task count and the previous-cycle comparison fields arrive
 * exactly as finalize stored them.
 *
 * @param median     batch value of the metric = median across the per-task medians
 * @param taskCount  number of tasks in the batch that reported this metric
 * @param prevMedian same metric in the previous-cycle batch, null when that batch
 *                   never reported it
 * @param delta      median minus prevMedian, null whenever prevMedian is null
 */
public record MobilePerfMetricView(BigDecimal median,
                                   Integer taskCount,
                                   BigDecimal prevMedian,
                                   BigDecimal delta) {
}
