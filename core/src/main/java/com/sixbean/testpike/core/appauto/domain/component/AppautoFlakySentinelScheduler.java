package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.appauto.api.dto.AppautoStabilityScanResult;
import com.sixbean.testpike.core.appauto.domain.service.AppautoStabilityService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AppautoFlakySentinelScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppautoFlakySentinelScheduler.class);

    private final AppautoStabilityService stabilityService;

    private final JobRunner jobRunner;

    public AppautoFlakySentinelScheduler(AppautoStabilityService stabilityService,
                                         JobRunner jobRunner) {
        this.stabilityService = stabilityService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "appautoFlakySentinelDaily", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${testpike.appauto.flaky-sentinel.cron:0 10 3 * * *}")
    public void detectDaily() {
        try {
            AppautoStabilityScanResult result = stabilityService.scan();
            if (result.flakyGroups() > 0) {
                log.info("APP flaky 哨兵每日扫描：用例×设备窗口 {} 组，命中翻转建议 {} 组（建议只读不自动改判，AI 翻译键={})",
                        result.groupsScanned(), result.flakyGroups(),
                        result.aiSuggestionEnabled() ? "开" : "关");
            }
        } catch (Exception e) {
            log.warn("APP flaky 哨兵每日扫描失败: {}", e.getMessage());
            jobRunner.alert("appautoFlakySentinelDaily", e);
        }
    }
}
