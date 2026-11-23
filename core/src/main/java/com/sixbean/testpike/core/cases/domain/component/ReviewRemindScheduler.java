package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.cases.api.dto.ReviewRemindResult;
import com.sixbean.testpike.core.cases.domain.service.ReviewRemindService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ReviewRemindScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReviewRemindScheduler.class);

    private final ReviewRemindService reviewRemindService;

    private final JobRunner jobRunner;

    public ReviewRemindScheduler(ReviewRemindService reviewRemindService, JobRunner jobRunner) {
        this.reviewRemindService = reviewRemindService;
        this.jobRunner = jobRunner;
    }

    /** Scan at the top of every hour (second, minute, hour, day, month, day of week). */
    @SchedulerLock(name = "reviewRemindHourly", lockAtMostFor = "PT50M")
    @Scheduled(cron = "${testpike.review.remind-cron:0 0 * * * *}")
    public void scanHourly() {
        try {
            ReviewRemindResult result = reviewRemindService.scan();
            if (result.reminded() > 0 || result.escalated() > 0) {
                log.info("评审催办扫描：待办 {} 条，催办 {} 条，升级 {} 条，同日去重跳过 {} 条",
                        result.scanned(), result.reminded(), result.escalated(), result.deduped());
            }
        } catch (Exception e) {
            log.warn("评审催办扫描失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("reviewRemindHourly", e);
        }
    }
}
