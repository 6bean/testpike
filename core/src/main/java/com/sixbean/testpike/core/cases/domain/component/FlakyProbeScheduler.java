package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.cases.api.dto.FlakyProbeSweepResult;
import com.sixbean.testpike.core.cases.domain.service.FlakyProbeService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flaky re-probe ledger scheduler .
 * <p>Same mounting style as {@link FlakyDetectionScheduler}: configurable cron +
 * {@code @SchedulerLock} against overlapping instances + the scheduling master switch
 * (no new framework). The hot key {@code ai.flaky.probe.enabled} is evaluated inside the
 * service every round, so turning the loop off stops writes without a restart or cron edit.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class FlakyProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlakyProbeScheduler.class);

    private final FlakyProbeService flakyProbeService;

    private final JobRunner jobRunner;

    public FlakyProbeScheduler(FlakyProbeService flakyProbeService, JobRunner jobRunner) {
        this.flakyProbeService = flakyProbeService;
        this.jobRunner = jobRunner;
    }

    /** Every 15 minutes by default (second minute hour day month weekday). */
    @SchedulerLock(name = "flakyProbeSweep", lockAtMostFor = "PT10M")
    @Scheduled(cron = "${testpike.flaky.probe-cron:0 */15 * * * *}")
    public void sweep() {
        try {
            FlakyProbeSweepResult result = flakyProbeService.sweep();
            if (result.disabled()) {
                return; // loop switched off by the hot key: silent absence, no log noise per round
            }
            if (result.opened() + result.closed() + result.folded()
                    + result.proposed() + result.withdrew()
                    + result.dispatched() + result.probeSkipped() > 0) {
                log.info("Flaky 回探台账扫描：新开 {} 个会话，收口 {} 个，折叠结果 {} 个会话，"
                                + "转正建议 {} 个（待人工在用例库确认），撤回建议 {} 个，"
                                + "派发回探 {} 个，暂缓 {} 个",
                        result.opened(), result.closed(), result.folded(),
                        result.proposed(), result.withdrew(),
                        result.dispatched(), result.probeSkipped());
            }
        } catch (Exception e) {
            log.warn("Flaky 回探台账扫描失败: {}", e.getMessage());
            jobRunner.alert("flakyProbeSweep", e);
        }
    }
}
