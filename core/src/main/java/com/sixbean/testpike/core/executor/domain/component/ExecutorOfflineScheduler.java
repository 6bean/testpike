package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ExecutorOfflineScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExecutorOfflineScheduler.class);

    private final ExecutorService executorService;

    private final JobRunner jobRunner;
    private final ExecTaskService execTaskService;

    public ExecutorOfflineScheduler(ExecutorService executorService, ExecTaskService execTaskService, JobRunner jobRunner) {
        this.executorService = executorService;
        this.execTaskService = execTaskService;
        this.jobRunner = jobRunner;
    }

    @SchedulerLock(name = "executorOfflineSweep", lockAtMostFor = "PT1M")
    @Scheduled(fixedDelay = 15_000)
    public void sweepOffline() {
        try {
            int offline = executorService.detectOfflineAndNotify();
            if (offline > 0) {
                log.warn("执行器离线检测：{} 个执行器心跳超时，已置离线并告警系统管理员", offline);
            }
            int redistributed = execTaskService.redistributeTasksOfOfflineExecutors();
            if (redistributed > 0) {
                log.warn("失联任务重派：{} 个离线执行器名下在跑任务已回待派发队列（IT-BATCH-07）", redistributed);
            }
        } catch (Exception e) {
            log.warn("执行器离线检测失败: {}", e.getMessage());
            jobRunner.alert("executorOfflineSweep", e);
        }
    }
}
