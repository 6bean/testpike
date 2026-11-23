package com.sixbean.testpike.core.linkage.domain.component;

import com.sixbean.testpike.core.linkage.api.dto.LinkageUrgeResult;
import com.sixbean.testpike.core.linkage.domain.service.LinkageUrgeService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly urge/escalation round over the downstream-impact ledger (;
 * ReviewRemindScheduler posture — the business entry {@link #scanHourly}
 * is shared with the run-now picker and the catalog keeps one description
 * row per job name).
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class LinkageUrgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(LinkageUrgeScheduler.class);

    /** Scheduler lock name; must stay identical to the catalog/registry key. */
    public static final String JOB_NAME = "linkageUrgeHourly";

    private final LinkageUrgeService linkageUrgeService;
    private final JobRunner jobRunner;

    public LinkageUrgeScheduler(LinkageUrgeService linkageUrgeService, JobRunner jobRunner) {
        this.linkageUrgeService = linkageUrgeService;
        this.jobRunner = jobRunner;
    }

    /** Top of every hour; the calendar-day dedup keys make the hourly beat a daily action. */
    @SchedulerLock(name = JOB_NAME, lockAtMostFor = "PT50M")
    @Scheduled(cron = "${testpike.linkage.urge-cron:0 0 * * * *}")
    public void scanHourly() {
        try {
            LinkageUrgeResult result = linkageUrgeService.scan();
            if (result.urged() > 0 || result.escalated() > 0) {
                log.info("联动催办扫描：待处置 {} 条，催办 {} 条，升级 {} 条，同日去重跳过 {} 条",
                        result.scanned(), result.urged(), result.escalated(), result.deduped());
            }
        } catch (Exception e) {
            log.warn("联动催办扫描失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert(JOB_NAME, e);
        }
    }
}
