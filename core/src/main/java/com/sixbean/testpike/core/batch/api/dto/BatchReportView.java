package com.sixbean.testpike.core.batch.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Batch-level summary report view (GET /batches/{id}/report).
 * <p>Data source=tp_batch_report <b>direct read of the snapshot row at closeout</b> (no recomputation at query time=same row as the completion notification, criterion 2).
 * The pass-rate calculation is single-sourced from PassRateSql.RATE_EXPR (injected on the write path).
 *
 * @param batchId batch id.
 * @param batchStatus batch status code at closeout (6 completed / 7 partial failure).
 * @param batchStatusLabel Chinese batch status name (single-sourced from BatchInfo.statusLabel).
 * @param total total number of tasks.
 * @param passCount success count.
 * @param failCount failure count.
 * @param skipCount skipped count.
 * @param timeoutCount timeout count.
 * @param passRate pass rate 0 to 1 (original snapshot value; NULL=no terminal-state tasks).
 * @param failDist four-category error_type distribution of failed/timed-out tasks (key=category code string).
 * The value "0" means uncategorized, and a missing key means zero tasks in that category.
 * @param attr AI attribution ratios for failed/timed-out tasks (key=attribution code/"unclassified"; missing key=zero entries).
 * @param durationMs batch duration in milliseconds (closeout snapshot; if either endpoint is missing=NULL).
 * @param envVersionSnapshot environment version snapshot at closeout (nullable).
 * @param monitorEvidence same-period monitoring attribution evidence (derived at read time, not a snapshot field).
 * It is the intersection of episodes with the batch execution window, scoped by the batch's environment, and ordered by start time descending.
 * Batches without environment/no red-light alert return an empty list, not null, and snapshot row fields must not be modified by the read path.
 * @param mockTaskCount Number of tasks whose result evidence carries a
 * non-empty mockNotes bucket (V238 snapshot column,
 * read verbatim — ⑤c report badge data source)
 * @param llmTaskCount Number of tasks whose mockNotes carry a source=llm
 * provenance row (snapshot column, read verbatim — the
 * report badge for how much of the batch was AI-generated)
 * @param mobilePerf batch mobile sampling rollup deserialized from the
 * V486 snapshot column (median per metric with task
 * count, plus prevMedian/delta when the previous cycle
 * reported the same metric). Read verbatim — null when
 * no task reported a mobile metric, and the bridge and
 * the report page never recompute it
 */
public record BatchReportView(long batchId, int batchStatus, String batchStatusLabel,
                              int total, int passCount, int failCount, int skipCount,
                              int timeoutCount, BigDecimal passRate,
                              Map<String, Integer> failDist, Map<String, Integer> attr,
                              Long durationMs, String envVersionSnapshot,
                              java.util.List<com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView>
                                      monitorEvidence,
                              int mockTaskCount, int llmTaskCount,
                              MobilePerfRollupView mobilePerf) {
}
