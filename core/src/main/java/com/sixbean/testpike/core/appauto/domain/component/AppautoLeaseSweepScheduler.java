package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AppautoLeaseSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppautoLeaseSweepScheduler.class);

    private final AppautoLeaseRunRecorder leaseRunRecorder;
    private final JobRunner jobRunner;

    public AppautoLeaseSweepScheduler(AppautoLeaseRunRecorder leaseRunRecorder, JobRunner jobRunner) {
        this.leaseRunRecorder = leaseRunRecorder;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "appautoLeaseSweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${testpike.appauto.lease-sweep-cron:*/30 * * * * *}")
    public void sweep() {
        try {
            sweepExpiredDeviceLeases();
        } catch (Exception e) {
            log.warn("设备租约周期回收失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("appautoLeaseSweep", e);
        }
    }

    
    public int sweepExpiredDeviceLeases() {
        int swept = leaseRunRecorder.sweepExpired();
        if (swept > 0) {
            log.info("设备租约周期回收：本轮释放 {} 台断线占用（流水已归因 EXPIRED）", swept);
        }
        return swept;
    }
}
