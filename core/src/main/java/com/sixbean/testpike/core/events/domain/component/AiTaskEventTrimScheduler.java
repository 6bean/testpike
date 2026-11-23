package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily heartbeat for the process-event ledger trim.
 * <p>Why this class exists separately from {@link AiTaskEventTrimSweeper}: the
 * sweeper is the business entry the tests and the owning delete paths call, so
 * it must always be present in the context; only the cron tick is optional
 * ({@code RetentionCleanupScheduler}/{@code DocumentExtractStallSweeper}
 * precedent — heartbeat and decision kept apart).
 * <p>Cadence: the trim is a housekeeping sweep over rows the reader already
 * bounded by window, so once a day at 03:30 is enough; the cron expression is
 * overridable through {@code testpike.events.trim-cron} (shipped default here)
 * and both limits it enforces are re-read from their hot keys on every round,
 * so adjusting a limit never needs a restart or a cron change.
 * <p>Failure posture: an outage logs and alerts through {@link JobRunner} and
 * leaves the next round to continue — both faces are idempotent, so a missed
 * night never needs a manual catch-up run.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AiTaskEventTrimScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiTaskEventTrimScheduler.class);

    private final AiTaskEventTrimSweeper sweeper;
    private final JobRunner jobRunner;

    public AiTaskEventTrimScheduler(AiTaskEventTrimSweeper sweeper, JobRunner jobRunner) {
        this.sweeper = sweeper;
        this.jobRunner = jobRunner;
    }

    /** Daily trim round of the process-event ledger (seconds minutes day ...). */
    @SchedulerLock(name = "aiTaskEventTrim", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "${testpike.events.trim-cron:0 30 3 * * *}")
    public void sweep() {
        try {
            sweeper.trimAll();
        } catch (Exception e) {
            log.warn("过程事件台账裁剪失败（下轮自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("aiTaskEventTrim", e);
        }
    }
}
