package com.sixbean.testpike.core.retention.domain.component;

import com.sixbean.testpike.core.retention.api.dto.RetentionItem;
import com.sixbean.testpike.core.retention.domain.service.RetentionService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RetentionCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupScheduler.class);

    private final RetentionService retentionService;

    private final JobRunner jobRunner;

    public RetentionCleanupScheduler(RetentionService retentionService, JobRunner jobRunner) {
        this.retentionService = retentionService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "retentionCleanupDaily", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${testpike.retention.cleanup-cron:0 30 3 * * *}")
    public void cleanupDaily() {
        try {
            List<RetentionItem> items = retentionService.run();
            if (items.stream().anyMatch(i -> i.count() > 0)) {
                log.info("每日保留清理完成：{}", items);
            }
        } catch (Exception e) {
            log.warn("保留清理失败（下轮自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("retentionCleanupDaily", e);
        }
    }
}
