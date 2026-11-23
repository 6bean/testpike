package com.sixbean.testpike.core.spec.domain.component;

import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import com.sixbean.testpike.core.spec.api.dto.SubscriptionScanResult;
import com.sixbean.testpike.core.spec.domain.service.SpecSubscriptionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SpecSubscriptionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpecSubscriptionScheduler.class);

    private final SpecSubscriptionService subscriptionService;
    private final JobRunner jobRunner;

    public SpecSubscriptionScheduler(SpecSubscriptionService subscriptionService, JobRunner jobRunner) {
        this.subscriptionService = subscriptionService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "specSubscriptionScan", lockAtMostFor = "PT55S")
    @Scheduled(cron = "${testpike.spec.subscription-cron:0 * * * * *}")
    public void scanMinute() {
        try {
            SubscriptionScanResult result = subscriptionService.scanDue();
            if (result.scanned() > 0) {
                log.info("契约订阅扫描：到期 {} 条（更新 {} / 无变化 {} / 失败 {}）",
                        result.scanned(), result.updated(), result.unchanged(), result.failed());
            }
        } catch (Exception e) {
            log.warn("契约订阅扫描失败（下轮自动重试，逐订阅幂等）: {}", e.getMessage());
            jobRunner.alert("specSubscriptionScan", e);
        }
    }
}
