package com.sixbean.testpike.admin.batch.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 *
 * @param mockTaskCount Tasks served under mocks (V238 snapshot column read straight
 * from core; 0 = plain batch. Badge data source — the
 * bridge never recomputes it)
 * @param llmTaskCount Tasks answered by the paid LLM fallback rung (source=llm
 * provenance snapshot; a subset of mockTaskCount. Read
 * straight from core — zero recomputation on the bridge)
 * @param mobilePerf Batch mobile sampling rollup (V486 snapshot column, read
 * verbatim by core and forwarded as-is: median per metric
 * with task count, plus prevMedian/delta when the previous
 * cycle reported the same metric). Null = the batch reported
 * no mobile metric; the report page renders no performance
 * block for it instead of zeros
 */
public record BatchReportView(long batchId, int batchStatus, String batchStatusLabel,
                              int total, int passCount, int failCount, int skipCount,
                              int timeoutCount, BigDecimal passRate,
                              Map<String, Integer> failDist, Map<String, Integer> attr,
                              Long durationMs, String envVersionSnapshot,
                              java.util.List<MonitorEpisodeView> monitorEvidence,
                              int mockTaskCount, int llmTaskCount,
                              MobilePerfRollupView mobilePerf) {
}
