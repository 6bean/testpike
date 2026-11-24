package com.sixbean.testpike.core.aichat.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Scheduler shell for the credential-screenshot compensating sweep.
 * <p>The governance body is a plain unconditional component — the inline
 * terminal-state purge is business wiring and must exist even when test
 * contexts disable scheduling (11-12 lesson: a cleaner injected into
 * services may never carry the scheduling switch). Only this shell is
 * gated, following the RetentionCleanupScheduler / AuditRetentionCleaner
 * separation precedent.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class CredentialScreenshotSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(CredentialScreenshotSweepScheduler.class);

    private final CredentialScreenshotGovernance governance;
    private final com.sixbean.testpike.core.sched.domain.component.JobRunner jobRunner;

    public CredentialScreenshotSweepScheduler(CredentialScreenshotGovernance governance,
            com.sixbean.testpike.core.sched.domain.component.JobRunner jobRunner) {
        this.governance = governance;
        this.jobRunner = jobRunner;
    }

    /** Hour-of-day offset 7 keeps the pass off the 03:30 retention cleanup window. */
    @SchedulerLock(name = "credentialScreenshotSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Scheduled(cron = "${testpike.chat.credential-sweep-cron:0 7/15 * * * *}")
    public void sweep() {
        try {
            int purged = governance.sweepPastGrace(LocalDateTime.now());
            if (purged > 0) {
                log.info("凭证截图原图补扫：本轮按锚精删 {} 个对象", purged);
            }
        } catch (RuntimeException ex) {
            // A failing pass never kills the schedule; the next pass retries
            // the same rows (delete is idempotent, ledger stays).
            log.warn("凭证截图补扫本轮未完成，下轮续清: {}", ex.toString());
            jobRunner.alert("credentialScreenshotSweep", ex);
        }
    }
}
