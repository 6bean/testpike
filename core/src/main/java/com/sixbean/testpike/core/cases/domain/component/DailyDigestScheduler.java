package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.cases.api.dto.DailyDigestResult;
import com.sixbean.testpike.core.cases.domain.service.DailyDigestService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DailyDigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyDigestScheduler.class);

    private final DailyDigestService dailyDigestService;

    private final JobRunner jobRunner;

    public DailyDigestScheduler(DailyDigestService dailyDigestService, JobRunner jobRunner) {
        this.dailyDigestService = dailyDigestService;
        this.jobRunner = jobRunner;
    }

    /** Scans daily at 08:00 (second minute hour day month weekday). */
    @Scheduled(cron = "${testpike.digest.daily-cron:0 0 8 * * *}")
    @SchedulerLock(name = "dailyDigestScan", lockAtMostFor = "PT2H")
    public void scanDaily() {
        try {
            DailyDigestResult result = dailyDigestService.scan();
            if (result.sent() > 0 || result.deduped() > 0) {
                log.info("每日日报扫描：有数据项目 {} 个，跳发 {} 个，发送 {} 条，同日去重 {} 条，偏好过滤 {} 条",
                        result.scanned(), result.skipped(), result.sent(),
                        result.deduped(), result.prefFiltered());
            }
        } catch (Exception e) {
            log.warn("每日日报扫描失败（下轮自动重试，幂等安全）: {}", e.getMessage());
            jobRunner.alert("dailyDigestScan", e);
        }
    }
}
