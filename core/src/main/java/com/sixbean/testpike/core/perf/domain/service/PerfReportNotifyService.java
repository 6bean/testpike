package com.sixbean.testpike.core.perf.domain.service;


public interface PerfReportNotifyService {

    
    int sendCard(long projectId, long runId);

    /**
     * SLA red-light automatic alert (GB-3, K2 subscription push): after a run reaches
     * its terminal state, push a breach card to the run operator ∪ project test owner ∪
     * project creator when the pinned-SLA verdict says the run did NOT pass.
     * <p>Verdict consumed from the same report assembly single source — a passing run
     * (including one whose snapshot carries no SLA gates, where the judge skips gates
     * and reports passed=true) sends nothing: never a fabricated red light.
     * dedup_key prefix = {@code PERF_SLA_BREACH:{runId}}, kept separate from the manual
     * {@code PERF_REPORT_CARD:{runId}} spectrum so the two channels never swallow each other.
     *
     * @return recipient count for the breach card; 0 when the run passed (honest silence)
     */
    int sendSlaBreachCard(long projectId, long runId);
}
