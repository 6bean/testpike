package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduling shell for the login-state keep-alive round.
 *
 * <p>Catch shape from the LoginStateRetentionSweeper precedent: the
 * cron gate lives HERE (never on the injected business bean), errors
 * are caught at the shell edge and routed through JobRunner.alert, and
 * @SchedulerLock keeps multi-instance deployments single-runner. The
 * fixed 60s tick only scans due rows - the row's own effective interval
 * (with backoff multiplier and id-phase jitter) decides who actually
 * fires, so the tick rate is a pacing resolution knob, not the cadence.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class LoginStateKeepAliveScheduler {

    private static final Logger log = LoggerFactory.getLogger(LoginStateKeepAliveScheduler.class);

    private final LoginStateKeepAliveRunner runner;
    private final JobRunner jobRunner;

    public LoginStateKeepAliveScheduler(LoginStateKeepAliveRunner runner, JobRunner jobRunner) {
        this.runner = runner;
        this.jobRunner = jobRunner;
    }

    /** Every-minute due scan; per-row windows decide the actual firings. */
    @SchedulerLock(name = "loginStateKeepAlive", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Scheduled(fixedDelayString = "${testpike.session.keepalive-tick-ms:60000}")
    public void tick() {
        try {
            int fired = runner.runRound();
            if (fired > 0) {
                log.info("登录态保活轮：{} 行到点已探测", fired);
            }
        } catch (Exception e) {
            log.warn("登录态保活轮失败（下轮自动重试，行级节奏锚保证不重复外发）: {}", e.getMessage());
            jobRunner.alert("loginStateKeepAlive", e);
        }
    }
}
