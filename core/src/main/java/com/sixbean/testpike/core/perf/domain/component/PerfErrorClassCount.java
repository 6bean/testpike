package com.sixbean.testpike.core.perf.domain.component;

/**
 * One error-class row of the run-level TopN aggregate (B-6 error drawer read shape).
 * <p>The class word is the fixed five-key vocabulary of the ingestion contract
 * (timeout/refused/http4xx/http5xx/biz, 03 §2.4) — one vocabulary, no per-page
 * coinages; the count is the summed occurrences across un-split frames only
 * (step lines are excluded so the same requests never count twice).
 *
 * @param errorClass error-class key as collected by the executor
 * @param count      summed occurrences over the run (always &gt; 0 — classes with no
 *                   collected occurrence simply do not appear, no fabricated zero rows)
 */
public record PerfErrorClassCount(String errorClass, long count) {
}
