package com.sixbean.testpike.core.webelement.domain.service;

/**
 * Whole-site survey batch-close attribution read-out leg.
 * <p>When a survey run reaches a terminal state while the page risk ledger
 * holds observed facts for it (out-of-baseline write requests, 4xx/5xx or
 * broken connections), the run's numbers are assembled core-side and handed
 * to the existing insight door for a short explanation paragraph, stored
 * once on the run row. No second attribution engine, no recomputation on
 * the model side.
 */
public interface SiteSurveyAttributionService {

    /**
     * Sweep terminal survey runs that carry risk facts but no attribution
     * summary yet: one door hop per run, newest first, bounded batch.
     *
     * @return number of runs that received a summary in this sweep
     */
    int sweepOnce();
}
