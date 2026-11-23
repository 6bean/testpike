package com.sixbean.testpike.core.sample.domain.component;

import com.sixbean.testpike.core.sample.api.dto.WordingAdoptionStat;
import com.sixbean.testpike.core.sample.domain.service.TrainingSampleService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class PromptAdoptionSummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PromptAdoptionSummaryScheduler.class);

    private final TrainingSampleService trainingSampleService;

    private final JobRunner jobRunner;

    public PromptAdoptionSummaryScheduler(TrainingSampleService trainingSampleService, JobRunner jobRunner) {
        this.trainingSampleService = trainingSampleService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "promptAdoptionSummaryDaily", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${testpike.sample.adoption-summary-cron:0 0 5 * * *}")
    public void summarizeDaily() {
        try {
            List<WordingAdoptionStat> stats = trainingSampleService.summaryWordingAdoption();
            if (!stats.isEmpty()) {
                log.info("D5 措辞采纳每日汇总：{} 个项目有采纳量（项目/措辞驳回/差异配对）={}",
                        stats.size(), stats);
            }
        } catch (Exception e) {
            log.warn("措辞采纳汇总失败（下轮自动重试，只读幂等）: {}", e.getMessage());
            jobRunner.alert("promptAdoptionSummaryDaily", e);
        }
    }
}
