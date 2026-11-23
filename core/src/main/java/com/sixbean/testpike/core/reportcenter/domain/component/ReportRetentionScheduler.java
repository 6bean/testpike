package com.sixbean.testpike.core.reportcenter.domain.component;

import com.sixbean.testpike.core.reportcenter.domain.service.ReportRetentionService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ReportRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportRetentionScheduler.class);

    private final ReportRetentionService reportRetentionService;

    private final JobRunner jobRunner;

    public ReportRetentionScheduler(ReportRetentionService reportRetentionService, JobRunner jobRunner) {
        this.reportRetentionService = reportRetentionService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "reportRetentionDaily", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${testpike.report.retention-cron:0 30 4 * * *}")
    public void purgeExpired() {
        try {
            int deleted = reportRetentionService.deleteExpired();
            if (deleted > 0) {
                log.info("报表快照保留清理：{} 行已删除", deleted);
            }
        } catch (Exception e) {
            log.warn("报表快照保留清理失败（次日自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("reportRetentionDaily", e);
        }
    }
}
