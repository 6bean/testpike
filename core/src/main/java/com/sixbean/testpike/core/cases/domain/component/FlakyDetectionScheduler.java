package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.cases.api.dto.FlakyDetectionResult;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class FlakyDetectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlakyDetectionScheduler.class);

    private final CaseService caseService;

    private final JobRunner jobRunner;

    public FlakyDetectionScheduler(CaseService caseService, JobRunner jobRunner) {
        this.caseService = caseService;
        this.jobRunner = jobRunner;
    }

    /** Executes daily at 02:30 (second, minute, hour, day, month, weekday). */
    @SchedulerLock(name = "flakyDetectionDaily", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${testpike.flaky.detect-cron:0 30 2 * * *}")
    public void detectDaily() {
        try {
            FlakyDetectionResult result = caseService.detectFlaky();
            if (result.marked() > 0 || result.suggestClear() > 0) {
                log.info("Flaky 每日检测：扫描 {} 条，新标记 {} 条，建议解除 {} 条（解除需人工确认）",
                        result.scanned(), result.marked(), result.suggestClear());
            }
        } catch (Exception e) {
            log.warn("Flaky 每日检测失败: {}", e.getMessage());
            jobRunner.alert("flakyDetectionDaily", e);
        }
    }
}
