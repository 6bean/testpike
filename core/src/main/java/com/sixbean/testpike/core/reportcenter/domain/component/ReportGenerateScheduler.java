package com.sixbean.testpike.core.reportcenter.domain.component;

import com.sixbean.testpike.core.reportcenter.domain.service.ReportGenerateService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ReportGenerateScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerateScheduler.class);

    private final ReportGenerateService reportGenerateService;

    private final JobRunner jobRunner;

    public ReportGenerateScheduler(ReportGenerateService reportGenerateService, JobRunner jobRunner) {
        this.reportGenerateService = reportGenerateService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "reportWeeklyGenerate", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${testpike.report.weekly-cron:0 30 6 * * MON}")
    public void generateWeekly() {
        try {
            int generated = reportGenerateService.runWeekly();
            if (generated > 0) {
                log.info("报表中心周报生成完成：{} 期（含全局维）", generated);
            }
        } catch (Exception e) {
            log.warn("周报生成失败（下周自动重试，幂等覆盖）: {}", e.getMessage());
            jobRunner.alert("reportWeeklyGenerate", e);
        }
    }

    
    @SchedulerLock(name = "reportMonthlyGenerate", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${testpike.report.monthly-cron:0 30 6 1 * *}")
    public void generateMonthly() {
        try {
            int generated = reportGenerateService.runMonthly();
            if (generated > 0) {
                log.info("报表中心月报生成完成：{} 期（含全局维）", generated);
            }
        } catch (Exception e) {
            log.warn("月报生成失败（下月自动重试，幂等覆盖）: {}", e.getMessage());
            jobRunner.alert("reportMonthlyGenerate", e);
        }
    }
}
