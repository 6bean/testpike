package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic driver for the N4 drain-timeout fallback (T294a package 1).
 * <p>Split from the business body on purpose: the coordinator must exist even in
 * test contexts that disable scheduling, only this shell carries the switch
 * (RetentionCleanupScheduler / AuditRetentionCleaner precedent).
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ExecutorDrainSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExecutorDrainSweepScheduler.class);

    private final ExecutorDrainCoordinator coordinator;
    private final JobRunner jobRunner;

    public ExecutorDrainSweepScheduler(ExecutorDrainCoordinator coordinator, JobRunner jobRunner) {
        this.coordinator = coordinator;
        this.jobRunner = jobRunner;
    }

    @SchedulerLock(name = "executorDrainSweep", lockAtMostFor = "PT1M")
    @Scheduled(fixedDelay = 30_000)
    public void sweepStaleDrains() {
        try {
            int settled = coordinator.sweepStaleDrains();
            if (settled > 0) {
                log.warn("排空超时兜底：本轮处理 {} 个过期排空令牌（续期/回池明细见上游日志）", settled);
            }
        } catch (Exception e) {
            log.warn("排空超时兜底扫描失败: {}", e.getMessage());
            jobRunner.alert("executorDrainSweep", e);
        }
    }
}
