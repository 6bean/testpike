package com.sixbean.testpike.core.webelement.domain.component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic sweep door for the survey attribution read-out (same split as the
 * retention family: the business component stays unconditional, only this
 * scheduling wrapper honors the test-time scheduling switch, so contexts
 * that disable scheduling still find the ledger bean itself available).
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SiteSurveyAttributionSweepJob {

    private static final Logger log = LoggerFactory.getLogger(SiteSurveyAttributionSweepJob.class);

    private final SiteSurveyAttributionLedger attributionService;

    public SiteSurveyAttributionSweepJob(SiteSurveyAttributionLedger attributionService) {
        this.attributionService = attributionService;
    }

    @SchedulerLock(name = "siteSurveyAttributionSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Scheduled(fixedDelayString = "${testpike.site-survey.attribution-sweep-ms:600000}",
            initialDelayString = "${testpike.site-survey.attribution-initial-ms:120000}")
    public void sweep() {
        try {
            int written = attributionService.sweepOnce();
            if (written > 0) {
                log.info("survey attribution read-out stored runs={}", written);
            }
        } catch (Exception roundFailure) {
            log.warn("survey attribution sweep round skipped (next round retries, idempotent): {}",
                    roundFailure.getMessage());
        }
    }
}
