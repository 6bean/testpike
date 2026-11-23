package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.cluster.domain.service.UpgradePipelineAdvanceService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class UpgradePipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(UpgradePipelineScheduler.class);

    private final UpgradePipelineAdvanceService advanceService;
    private final JobRunner jobRunner;

    public UpgradePipelineScheduler(UpgradePipelineAdvanceService advanceService,
                                    JobRunner jobRunner) {
        this.advanceService = advanceService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "upgradePipelineAdvance", lockAtMostFor = "PT10M")
    @Scheduled(cron = "${testpike.cluster.upgrade-advance-cron:0 * * * * *}")
    public void advance() {
        try {
            advanceService.advanceOnce();
        } catch (Exception e) {
            log.warn("升级流水线推进扫描失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("upgradePipelineAdvance", e);
        }
    }
}
