package com.sixbean.testpike.core.perf.domain.service;

import java.util.List;
import java.util.Map;


public interface PerfReportService {

    
    Map<String, Object> build(long projectId, long runId);

    
    Map<String, Object> compare(long projectId, long runA, long runB);

    /**
     * Scenario trend read face : ascending rows of the scenario's
     * terminal runs (normal finish / breaker stop / manual stop; an in-flight run
     * never enters the rows), each row = whole-run percentile recomputation plus
     * weighted error rate plus the golden mark carried through.
     * <p>Percentile caliber = merged per-second histogram buckets read at nearest
     * rank (the same single math source the SLA gates use); frames without buckets
     * fall back to the per-second p95 max honestly annotated, p50/p99 stay null
     * instead of fabricated. Error rate = sum(fail)/sum(req) when bucket counts
     * exist, else the equal-weight per-second mean (same judge family, never a
     * second head). The window is the K8 key perf.trend.max-runs (default 200,
     * newest-first truncation bounds a thousand-run scenario; truncation is the
     * honest shape). Foreign/unknown scenario = NOT_FOUND in human wording with
     * no existence leak; no terminal runs = empty list.
     */
    List<Map<String, Object>> trend(long projectId, long scenarioId);

    /**
     * CI gate verdict read face : the pass/fail/pending answer a CI
     * pipeline gets for one run, riding the exact same SLA three-gate judge the
     * report verdict row uses — never a second judgment head.
     * <p>An in-flight run answers pending with the current status named (partial
     * data is never hard-judged); a VOID-marked run answers pending naming the
     * void as the reason (a voided run is never a gate basis); a terminal
     * non-void run answers pass/fail with breachedGates passed through and the
     * golden mark echoed for the pipeline badge. Thresholds ride the run's
     * pinned launch snapshot (same discipline as the report — scenario edits
     * never change an already-finished run's verdict). Foreign/unknown run =
     * NOT_FOUND in human wording with no existence leak (ownership rides
     * {@code PerfRunService.view} single point).
     */
    Map<String, Object> ciVerdict(long projectId, long runId);

    /**
     * Run-report export : render the exact same {@link #build}
     * read face into a downloadable document — pdf (via the shared
     * PdfHtmlRenderer, Noto Sans SC embedded) or html. Single-source discipline:
     * export never recomputes metrics, it serializes what the online report
     * shows, so the exported numbers can never diverge from the page.
     * <p>Unknown format = VALIDATION_ERROR naming the accepted formats;
     * foreign/unknown run rides the same NOT_FOUND wording as {@link #build}
     * (no existence leak).
     */
    byte[] export(long projectId, long runId, String format);
}
