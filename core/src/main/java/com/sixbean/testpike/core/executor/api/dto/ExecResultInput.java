package com.sixbean.testpike.core.executor.api.dto;

/**
 *
 * @param result pass | fail | manual_skip
 * [{secondOffset,rps,p95Ms,errorRate,ttftMs?,itlMs?,tokensPerSec?}]
 * @param mockNotes Mock-serving audit bucket collected by the executor's route
 * interception, one entry per served call
 * ({path,mode[,status]}); core aggregates it verbatim into
 * result_ext so batch reports can badge "passed under N mocks".
 * Absent or empty = key omitted, column stays NULL (trailing-key
 * compatibility precedent).
 * @param mobilePerfSummary -2 (03 trailing key) in-window mobile sampling rollup
 * {metrics:{<name>:{median,count}}, sampleCount}, computed once by
 * executor driver/mobile/perf_session.summarize_metrics. core stores
 * the numbers verbatim into result_ext and never re-parses raw
 * sample rows (a second extraction source would drift). Absent or
 * empty = key omitted, column stays NULL, so web and api tasks that
 * never sampled a device are untouched (trailing-key precedent).
 * @param sessionAuthRejections (03 trailing key, AV face 3) same-origin 401/403
 * browser responses relayed while a ledger login state was
 * injected ({url,status} rows, deduped, ≤5); core adjudicates
 * in SessionAuthRejectionWriteback (origin whitelist +
 * expected-status exemption) — this channel carries raw
 * observations, zero executor-side policy. Absent or empty =
 * key omitted, legacy relays untouched (trailing-key precedent).
 * @param elementAnchorHits (03 trailing key, WE one row per page-element
 * anchor attempt {@code [{elementId,name,viewport,anchorType,hit,tried}]}
 * (key set bilaterally pinned with the executor's
 * ANCHOR_HIT_ROW_KEYS); core feeds the per-(viewport,anchorType)
 * stat account and recomputes the smoothed confidence from them.
 * Absent or empty = key omitted (trailing-key precedent).
 * @param stepHealEvidence (03 trailing key) direct-heal evidence rows
 * {@code [{healed,firstAnchorFailed,elementId,stepNo,fromAnchor,usedAnchor,viewport,ledgerRevision,similarityScore}]}
 * (key set bilaterally pinned with the executor's
 * HEAL_EVIDENCE_ROW_KEYS); core stores them verbatim via
 * StepHealEvidenceWriteback into tp_exec_step_heal_evidence
 * (V554) as the audit trail behind a self-healed run.
 * Absent or empty = key omitted (trailing-key precedent).
 */
public record ExecResultInput(long taskId, String claimToken, String result,
                              Integer errorType, String errorDetail,
                              Object stepsTimeline, Object screenshots, Integer durationMs,
                              String log,
                              Object consoleViolations, Object netHits, Object dbDigest,
                              Object protocolSnapshots, Object vars,
                              Object perfFrames, Object perfEvents, Integer perfStatus,
                              Object recordEvents, Object mockNotes,
                              Object mobilePerfSummary,
                              Object sessionAuthRejections,
                              Object elementAnchorHits,
                              Object stepHealEvidence) {

 /**
  * 22-arg compatibility constructor: executors built before the heal-evidence
  * trailing key never send stepHealEvidence, so the evidence plane stays
  * absent and every legacy relay keeps its exact shape (trailing-key
  * compatibility precedent).
  */
 public ExecResultInput(long taskId, String claimToken, String result,
                        Integer errorType, String errorDetail,
                        Object stepsTimeline, Object screenshots, Integer durationMs,
                        String log, Object consoleViolations, Object netHits,
                        Object dbDigest, Object protocolSnapshots, Object vars,
                        Object perfFrames, Object perfEvents, Integer perfStatus,
                        Object recordEvents, Object mockNotes,
                        Object mobilePerfSummary, Object sessionAuthRejections,
                        Object elementAnchorHits) {
     this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
             screenshots, durationMs, log, consoleViolations, netHits, dbDigest,
             protocolSnapshots, vars, perfFrames, perfEvents, perfStatus, recordEvents,
             mockNotes, mobilePerfSummary, sessionAuthRejections, elementAnchorHits, null);
 }

 /**
  * 21-arg compatibility constructor: executors built before the element-anchor
  * hit trailing key never send elementAnchorHits, so the outcome plane stays
  * absent and every legacy relay keeps its exact shape (trailing-key
  * compatibility precedent).
  */
 public ExecResultInput(long taskId, String claimToken, String result,
                        Integer errorType, String errorDetail,
                        Object stepsTimeline, Object screenshots, Integer durationMs,
                        String log, Object consoleViolations, Object netHits,
                        Object dbDigest, Object protocolSnapshots, Object vars,
                        Object perfFrames, Object perfEvents, Integer perfStatus,
                        Object recordEvents, Object mockNotes,
                        Object mobilePerfSummary, Object sessionAuthRejections) {
     this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
             screenshots, durationMs, log, consoleViolations, netHits, dbDigest,
             protocolSnapshots, vars, perfFrames, perfEvents, perfStatus, recordEvents,
             mockNotes, mobilePerfSummary, sessionAuthRejections, null);
 }

 /**
  * 20-arg compatibility constructor: executors built before the auth-rejection
  * trailing key never send sessionAuthRejections, so the evidence plane stays
  * absent and every legacy relay keeps its exact shape (trailing-key
  * compatibility precedent).
  */
 public ExecResultInput(long taskId, String claimToken, String result,
                        Integer errorType, String errorDetail,
                        Object stepsTimeline, Object screenshots, Integer durationMs,
                        String log, Object consoleViolations, Object netHits,
                        Object dbDigest, Object protocolSnapshots, Object vars,
                        Object perfFrames, Object perfEvents, Integer perfStatus,
                        Object recordEvents, Object mockNotes, Object mobilePerfSummary) {
     this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
             screenshots, durationMs, log, consoleViolations, netHits, dbDigest,
             protocolSnapshots, vars, perfFrames, perfEvents, perfStatus, recordEvents,
             mockNotes, mobilePerfSummary, null);
 }

 /**
  * 19-arg compatibility constructor: executors built before the mobile performance
  * trailing key never send mobilePerfSummary, so the key stays absent and result_ext
  * keeps exactly the shape it had before (trailing-key compatibility precedent).
  */
 public ExecResultInput(long taskId, String claimToken, String result,
                        Integer errorType, String errorDetail,
                        Object stepsTimeline, Object screenshots, Integer durationMs,
                        String log, Object consoleViolations, Object netHits,
                        Object dbDigest, Object protocolSnapshots, Object vars,
                        Object perfFrames, Object perfEvents, Integer perfStatus,
                        Object recordEvents, Object mockNotes) {
     this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
             screenshots, durationMs, log, consoleViolations, netHits, dbDigest,
             protocolSnapshots, vars, perfFrames, perfEvents, perfStatus, recordEvents,
             mockNotes, null);
 }

 /** 18-arg compatibility constructor: legacy executors never send mockNotes. */
 public ExecResultInput(long taskId, String claimToken, String result,
                        Integer errorType, String errorDetail,
                        Object stepsTimeline, Object screenshots, Integer durationMs,
                        String log, Object consoleViolations, Object netHits,
                        Object dbDigest, Object protocolSnapshots, Object vars,
                        Object perfFrames, Object perfEvents, Integer perfStatus,
                        Object recordEvents) {
     this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
             screenshots, durationMs, log, consoleViolations, netHits, dbDigest,
             protocolSnapshots, vars, perfFrames, perfEvents, perfStatus, recordEvents,
             null, null);
 }

 
    public ExecResultInput(long taskId, String claimToken, String result,
                           Integer errorType, String errorDetail,
                           Object stepsTimeline, Object screenshots, Integer durationMs,
                           String log, Object consoleViolations, Object netHits,
                           Object dbDigest, Object protocolSnapshots, Object vars,
                           Object perfFrames, Object perfEvents, Integer perfStatus) {
        this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
                screenshots, durationMs, log, consoleViolations, netHits, dbDigest,
                protocolSnapshots, vars, perfFrames, perfEvents, perfStatus, null);
    }

 
    public ExecResultInput(long taskId, String claimToken, String result,
                           Integer errorType, String errorDetail,
                           Object stepsTimeline, Object screenshots, Integer durationMs,
                           String log, Object consoleViolations, Object netHits,
                           Object dbDigest, Object protocolSnapshots, Object vars) {
        this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
                screenshots, durationMs, log, consoleViolations, netHits, dbDigest,
                protocolSnapshots, vars, null, null, null);
    }

 
    public ExecResultInput(long taskId, String claimToken, String result,
                           Integer errorType, String errorDetail,
                           Object stepsTimeline, Object screenshots, Integer durationMs,
                           String log, Object consoleViolations, Object netHits,
                           Object dbDigest, Object protocolSnapshots) {
        this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
                screenshots, durationMs, log, consoleViolations, netHits, dbDigest,
                protocolSnapshots, null);
    }

 
    public ExecResultInput(long taskId, String claimToken, String result,
                           Integer errorType, String errorDetail,
                           Object stepsTimeline, Object screenshots, Integer durationMs,
                           String log, Object consoleViolations, Object netHits,
                           Object dbDigest) {
        this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
                screenshots, durationMs, log, consoleViolations, netHits, dbDigest, null, null);
    }

 
    public ExecResultInput(long taskId, String claimToken, String result,
                           Integer errorType, String errorDetail,
                           Object stepsTimeline, Object screenshots, Integer durationMs,
                           String log) {
        this(taskId, claimToken, result, errorType, errorDetail, stepsTimeline,
                screenshots, durationMs, log, null, null, null, null, null);
    }
}
