package com.sixbean.testpike.aiservice.perfinsight.api.dto;

/**
 * Load-test run-insight request (B-11 ; fields mirror the core-side
 * orchestration request one-to-one).
 * <p>{@code factsBlock} is the plain-text attribution snapshot core assembled
 * from its single-source data (SLA conclusion, percentile/error-rate/throughput
 * row, error-class TopN, bottleneck hints) — this leg renders it into the
 * prompt and never re-derives or re-orders the numbers (red line 6: no second
 * attribution spec).
 *
 * @param projectId project anchor (LLM routing + call-log cost attribution)
 * @param factsBlock run attribution facts rendered by core, verbatim material
 * @param runId optional load-test run anchor (: when present the leg lands
 * its process-event tail keyed job_ref = run_token = this id;
 * null keeps legacy door calls a zero-touch no-op)
 */
public record PerfInsightRequest(
        Long projectId,
        String factsBlock,
        Long runId) {
}
