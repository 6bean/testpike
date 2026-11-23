package com.sixbean.testpike.core.monitor.domain.component;

import com.sixbean.testpike.core.monitor.domain.service.MonitorEpisodeService;
import com.sixbean.testpike.core.monitor.domain.service.MonitorTargetService;
import com.sixbean.testpike.core.monitor.domain.component.MonitorConfigReader;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class MonitorScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitorScanScheduler.class);

    private final MonitorTargetService monitorTargetService;
    private final MonitorEpisodeService monitorEpisodeService;
    private final MonitorConfigReader configReader;
    private final JobRunner jobRunner;

    public MonitorScanScheduler(MonitorTargetService monitorTargetService,
                                MonitorEpisodeService monitorEpisodeService,
                                MonitorConfigReader configReader, JobRunner jobRunner) {
        this.monitorTargetService = monitorTargetService;
        this.monitorEpisodeService = monitorEpisodeService;
        this.configReader = configReader;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "monitorScan", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    @Scheduled(cron = "${testpike.monitor.scan-cron:*/15 * * * * *}")
    public void scan() {
        monitorEpisodeService.markScanHeartbeat();
        try {
            int scanned = monitorTargetService.scanDueTargets();
            if (scanned > 0) {
                log.info("监控探测：本轮扫描 {} 个到期目标", scanned);
            }
        } catch (Exception e) {
            log.warn("监控探测扫描失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("monitorScan", e);
        }
    }

    
    @SchedulerLock(name = "monitorLostWatchdog", lockAtMostFor = "PT2M")
    @Scheduled(cron = "${testpike.monitor.lost-cron:0 * * * * *}")
    public void lostWatchdog() {
        try {
            int grace = configReader.intValue(MonitorConfigReader.KEY_SCAN_HEARTBEAT_SECONDS)
                    * configReader.intValue(MonitorConfigReader.KEY_STALL_MULTIPLIER);
            int sent = monitorEpisodeService.alertIfStalled(grace);
            if (sent > 0) {
                log.warn("监控中心失联：已向 {} 位系统管理员告警", sent);
            }
        } catch (Exception e) {
            log.warn("监控失联巡检失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("monitorLostWatchdog", e);
        }
    }

    
    @SchedulerLock(name = "monitorEnvVersionRecheck", lockAtMostFor = "PT5M")
    @Scheduled(cron = "${testpike.monitor.env-recheck-cron:0 */5 * * * *}")
    public void recheckEnvVersions() {
        try {
            int reset = monitorTargetService.resetStaleSnapshots();
            if (reset > 0) {
                log.info("监控调度复位：{} 个目标因环境档案变更退回基准频率", reset);
            }
        } catch (Exception e) {
            log.warn("监控环境档案漂移检查失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("monitorEnvVersionRecheck", e);
        }
    }
}
