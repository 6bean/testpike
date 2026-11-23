package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AppautoDeviceProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppautoDeviceProbeScheduler.class);

    private final AppautoDeviceService deviceService;
    private final JobRunner jobRunner;

    public AppautoDeviceProbeScheduler(AppautoDeviceService deviceService, JobRunner jobRunner) {
        this.deviceService = deviceService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "appautoDeviceProbe", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    @Scheduled(cron = "${testpike.appauto.device-probe-cron:0 * * * * *}")
    public void probe() {
        try {
            probeRemoteDevicesOnce();
        } catch (Exception e) {
            log.warn("远程 ADB 设备周期探活失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("appautoDeviceProbe", e);
        }
    }

    
    public int probeRemoteDevicesOnce() {
        int probed = deviceService.probeRemoteDevices();
        if (probed > 0) {
            log.debug("远程 ADB 设备探活：本轮握手 {} 台设备柜端点", probed);
        }
        return probed;
    }
}
