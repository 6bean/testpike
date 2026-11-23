package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.core.project.api.dto.EnvHealthScanResult;
import com.sixbean.testpike.core.project.domain.service.EnvHealthService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class EnvHealthScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(EnvHealthScanScheduler.class);

    private final EnvHealthService envHealthService;

    private final JobRunner jobRunner;

    public EnvHealthScanScheduler(EnvHealthService envHealthService, JobRunner jobRunner) {
        this.envHealthService = envHealthService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "envHealthScan", lockAtMostFor = "PT14M")
    @Scheduled(cron = "${testpike.env.health-cron:0 */15 * * * *}")
    public void scan() {
        try {
            EnvHealthScanResult result = envHealthService.scanAll();
            if (result.alerted() > 0 || result.recovered() > 0) {
                log.info("环境健康检查：扫描 {} 环境，健康 {}，异常 {}，告警 {}，恢复 {}",
                        result.scanned(), result.healthy(), result.unhealthy(),
                        result.alerted(), result.recovered());
            }
        } catch (Exception e) {
            log.warn("环境健康检查扫描失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("envHealthScan", e);
        }
    }

 
    @SchedulerLock(name = "certExpiryScan", lockAtMostFor = "PT5M")
    @Scheduled(cron = "${testpike.env.cert-expiry-cron:0 30 9 * * *}")
    public void scanCertExpiry() {
        try {
            int notified = envHealthService.scanCertExpiry();
            if (notified > 0) {
                log.info("证书到期扫描：发出 {} 条到期提醒", notified);
            }
        } catch (Exception e) {
            log.warn("证书到期扫描失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("certExpiryScan", e);
        }
    }
}
