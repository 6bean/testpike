package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.appauto.domain.service.AppautoReservationService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AppautoReservationSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppautoReservationSweepScheduler.class);

    private final AppautoReservationService reservationService;
    private final JobRunner jobRunner;

    public AppautoReservationSweepScheduler(AppautoReservationService reservationService,
                                            JobRunner jobRunner) {
        this.reservationService = reservationService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "appautoReservationSweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    @Scheduled(cron = "${testpike.appauto.reservation-sweep-cron:0 * * * * *}")
    public void sweep() {
        try {
            sweepExpiredReservations();
        } catch (Exception e) {
            log.warn("设备预约超时清扫失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("appautoReservationSweep", e);
        }
    }

    
    public int sweepExpiredReservations() {
        int swept = reservationService.sweepExpired();
        if (swept > 0) {
            log.info("设备预约超时清扫：本轮自动释放 {} 条超窗预约（已留系统释放痕）", swept);
        }
        return swept;
    }
}
