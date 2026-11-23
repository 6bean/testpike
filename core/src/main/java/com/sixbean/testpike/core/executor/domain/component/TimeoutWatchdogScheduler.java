package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TimeoutWatchdogScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimeoutWatchdogScheduler.class);

    private final ExecTaskService execTaskService;

    private final JobRunner jobRunner;
    private final BatchService batchService;

    public TimeoutWatchdogScheduler(ExecTaskService execTaskService, BatchService batchService, JobRunner jobRunner) {
        this.execTaskService = execTaskService;
        this.batchService = batchService;
        this.jobRunner = jobRunner;
    }

    @SchedulerLock(name = "execTimeoutWatchdog", lockAtMostFor = "PT2M")
    @Scheduled(fixedDelay = 30_000)
    public void sweep() {
        try {
            int timedOut = execTaskService.sweepTimeoutTasks();
            if (timedOut > 0) {
                log.warn("单条超时扫描：{} 个执行中任务已标记超时并释放占用（TC-I4-01）", timedOut);
            }
        } catch (Exception e) {
            log.warn("单条超时扫描失败: {}", e.getMessage());
            jobRunner.alert("execTimeoutWatchdog", e);
        }
        try {
            int cancelled = batchService.sweepWatchdogTimeoutBatches();
            if (cancelled > 0) {
                log.warn("批次看门狗：{} 个超期批次已取消（未执行任务打标出队，S8）", cancelled);
            }
        } catch (Exception e) {
            log.warn("批次看门狗扫描失败: {}", e.getMessage());
            jobRunner.alert("execTimeoutWatchdog", e);
        }
    }
}
