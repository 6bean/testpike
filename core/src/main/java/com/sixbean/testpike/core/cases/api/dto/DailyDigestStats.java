package com.sixbean.testpike.core.cases.api.dto;

/**
 * Four-factor statistics for a single-project daily report include yesterday's batch, new defects, pending review backlog, and coverage change.
 * The four-factor set is defined in the product requirements.
 * <p>The window fields (newCases/execPassed/execFailed/newDefects/newLinks) are yesterday's increments, and the snapshot fields are current outstanding data.
 * (openDefects/reviewBacklog) are current outstanding data (unclosed/backlog criteria), and empty-data determination considers only yesterday's increments.
 * (all zero -> skip sending and do not send an empty report); projects with non-empty outstanding data but no activity yesterday are not disturbed.
 *
 */
public record DailyDigestStats(int newCases, int execPassed, int execFailed,
                               int newDefects, int openDefects, int reviewBacklog, int newLinks) {

    /** Check whether there was no activity yesterday (skip-send decision; only increments participate, and snapshots do not trigger sending). */
    public boolean emptyYesterday() {
        return newCases == 0 && execPassed == 0 && execFailed == 0
                && newDefects == 0 && newLinks == 0;
    }
}
