package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import com.sixbean.testpike.core.webelement.domain.service.WebElementRetentionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily ledger retention sweep . Scheduling shell only —
 * the business door (WebElementRetentionService) stays unconditional so
 * tests and the manual run endpoint can reach it with scheduling disabled
 * (shared lesson from the AuditRetentionCleaner split). Window keys
 * element.candidate-ttl-days / element.retention-days are hot-read inside
 * the service on every run; cron offset is a hot-updatable property.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class WebElementRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebElementRetentionScheduler.class);

    private final WebElementRetentionService retentionService;
    private final JobRunner jobRunner;

    public WebElementRetentionScheduler(WebElementRetentionService retentionService, JobRunner jobRunner) {
        this.retentionService = retentionService;
        this.jobRunner = jobRunner;
    }

    /** Aged candidates retire, retired rows past the window leave the ledger. */
    @SchedulerLock(name = "webElementLedgerRetentionDaily", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${testpike.webelement.retention-cron:0 40 4 * * *}")
    public void sweep() {
        try {
            var result = retentionService.sweep();
            if (result.retiredCandidates() > 0 || result.purgedRows() > 0) {
                log.info("元素账本留存清理：{} 条候选自动退役，{} 条退役到期已清理",
                        result.retiredCandidates(), result.purgedRows());
            }
        } catch (Exception e) {
            log.warn("元素账本留存清理失败（次日自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("webElementLedgerRetentionDaily", e);
        }
    }
}
