package com.sixbean.testpike.core.sched.domain.component;

import com.sixbean.testpike.core.sched.domain.service.ScheduleFireService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduleTriggerScanner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleTriggerScanner.class);

    private final ScheduleFireService scheduleFireService;

    private final JobRunner jobRunner;

    public ScheduleTriggerScanner(ScheduleFireService scheduleFireService, JobRunner jobRunner) {
        this.scheduleFireService = scheduleFireService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "scheduleTriggerScan", lockAtMostFor = "PT55S")
    @Scheduled(cron = "${testpike.schedule.scan-cron:0 * * * * *}")
    public void scanMinute() {
        try {
            int fired = scheduleFireService.scanAndFire(Instant.now());
            if (fired > 0) {
                log.info("定时调度本轮触发 {} 个批次来源（回归计划/模板复用，留痕见触发记录列表）", fired);
            }
        } catch (Exception e) {
            log.warn("定时调度扫描失败（下轮自动重试，触发留痕幂等安全）: {}", e.getMessage());
            jobRunner.alert("scheduleTriggerScan", e);
        }
    }
}
