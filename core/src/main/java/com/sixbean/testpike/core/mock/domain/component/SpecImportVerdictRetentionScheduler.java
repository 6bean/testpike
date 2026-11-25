package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily heartbeat for the import-suggestion retention trim.
 * <p>Split from {@link SpecImportVerdictRetentionCleaner} on the proven
 * {@code AiTaskEventTrimScheduler} pattern: the cleaner is the business entry
 * tests and owners call (always present), only the cron tick is optional
 * behind {@code testpike.scheduling.enabled}.
 * <p>Idempotent by construction — a missed night needs no manual catch-up, and
 * the retention window is re-read from its hot key every round.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SpecImportVerdictRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpecImportVerdictRetentionScheduler.class);

    private final SpecImportVerdictRetentionCleaner cleaner;
    private final JobRunner jobRunner;

    public SpecImportVerdictRetentionScheduler(SpecImportVerdictRetentionCleaner cleaner,
                                               JobRunner jobRunner) {
        this.cleaner = cleaner;
        this.jobRunner = jobRunner;
    }

    /** Daily trim round of the import-suggestion table. */
    @SchedulerLock(name = "mockSpecImportVerdictTrim", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    @Scheduled(cron = "${testpike.mock.verdict-trim-cron:0 45 3 * * *}")
    public void sweep() {
        try {
            cleaner.trimExpired();
        } catch (Exception e) {
            log.warn("导入智能建议保留清理失败（下轮自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("mockSpecImportVerdictTrim", e);
        }
    }
}
