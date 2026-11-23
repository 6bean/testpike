package com.sixbean.testpike.core.cost.domain.component;

import com.sixbean.testpike.core.cost.domain.service.CostService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class CostOverrideExpireScheduler {

    private static final Logger log = LoggerFactory.getLogger(CostOverrideExpireScheduler.class);

    private final CostService costService;

    private final JobRunner jobRunner;

    public CostOverrideExpireScheduler(CostService costService, JobRunner jobRunner) {
        this.costService = costService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "costOverrideExpire", lockAtMostFor = "PT50M")
    @Scheduled(cron = "${testpike.cost.override-expire-cron:0 50 * * * *}")
    public void cleanExpiredOverrides() {
        try {
            int cleared = costService.clearExpiredOverrides();
            if (cleared > 0) {
                log.info("熔断临时恢复到期清理：{} 行已重熔留痕", cleared);
            }
        } catch (Exception e) {
            log.warn("临时恢复到期清理失败（下轮自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("costOverrideExpire", e);
        }
    }
}
