package com.sixbean.testpike.core.ai.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.ai.domain.service.AiTaskService;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stall sweep for zombie "generating" AI tasks (filed by the audit).
 * <p>Why this class exists: a generation task rides tp_ai_task.status=2 from
 * dispatch until ai-service writes the outcome back. If the consumer dies
 * mid-flight (service restart, message loss, killed LLM call) nobody reclaims
 * the row — the live audit found 12 rows idle beyond five hours (oldest 9
 * days) while the queue panel still said "generating", the backlog gauge
 * overstated and the user had no recovery path. This sweeper marks status=2
 * rows whose updated_at passed the stall window (K8 key
 * ai.task.running-stall-minutes, seeded 60 — far beyond any healthy single
 * generation) as failed through {@link AiTaskService#markFailed}, the single
 * write-back door: the FAILED state plus SERVICE_ERROR code keeps the restart
 * <p>Posture notes (DocumentExtractStallSweeper precedent):
 * <ul>
 * <li>heartbeat/decision separation: the cron fires every 2 minutes; the
 * real decision point is each row's own updated_at against the window
 * read fresh on every run (hot-update);</li>
 * <li>fail-closed parsing: blank/garbage falls back to the seeded default
 * 60; a non-positive window refuses the sweep instead of failing every
 * in-flight task (a mistyped "0" must never mean "fail all");</li>
 * <li>no second status-write path: candidates come from a read-only SELECT
 * and every settle goes through markFailed, whose own conditional
 * UPDATE (status=2 still at write time) makes a concurrent write-back
 * win — a row already done/failed/cancelled throws and is skipped;</li>
 * <li>the business entry is shared with the tests — the @Scheduled method
 * only adds catch + JobRunner alert.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AiTaskRunningStallSweeper {

    private static final Logger log = LoggerFactory.getLogger(AiTaskRunningStallSweeper.class);

    /** K8 key holding the stall window in minutes (V630 seeds 60). */
    public static final String STALL_MINUTES_KEY = "ai.task.running-stall-minutes";

    /** Seeded default; fail-closed fallback for unreadable values. */
    static final int DEFAULT_STALL_MINUTES = 60;

    /** Failure classification reused from the existing three-code set. */
    static final String STALL_ERROR_CODE = "SERVICE_ERROR";

    /** Human-readable recovery door shown on the task row (VARCHAR(500) safe). */
    static final String STALL_ERROR_TEXT =
            "生成任务疑似中断（超过停滞窗口仍无回写），已标记为失败；"
                    + "可在「AI 生成」页右侧「生成任务进度」面板对该行点「重新生成」再次发起。";

    /** tp_ai_task statuses touched by this sweep (document 01 state bits). */
    private static final int STATUS_RUNNING = 2;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final AiTaskService aiTaskService;
    private final JobRunner jobRunner;

    public AiTaskRunningStallSweeper(NamedParameterJdbcTemplate jdbc,
                                     ConfigService configService,
                                     AiTaskService aiTaskService,
                                     JobRunner jobRunner) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.aiTaskService = aiTaskService;
        this.jobRunner = jobRunner;
    }

    /** Periodic reclaim of stalled generation rows (same 2-minute cadence as the doc sweep). */
    @SchedulerLock(name = "aiTaskRunningStallSweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${testpike.ai.task.running-stall-cron:0 */2 * * * *}")
    public void sweep() {
        try {
            int reclaimed = sweepStalled();
            if (reclaimed > 0) {
                log.info("生成中僵尸任务清扫：{} 个超窗任务已落败态（可重新生成）", reclaimed);
            }
        } catch (Exception e) {
            log.warn("生成中僵尸任务清扫失败（下轮自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("aiTaskRunningStallSweep", e);
        }
    }

    /**
     * One reclaim round against the freshly read K8 window (business entry
     * shared by the cron catch and the tests).
     *
     * @return task rows settled to failed this round
     */
    public int sweepStalled() {
        int minutes = parseStallMinutes(configService.getValue(STALL_MINUTES_KEY,
                String.valueOf(DEFAULT_STALL_MINUTES)));
        return sweepWithWindow(minutes);
    }

    /**
     * Settle status=2 rows idle for more than {@code minutes}. Non-positive
     * windows are refused (keep-everything semantics — never fail-everything).
     * Every settle rides AiTaskService.markFailed, so a row whose write-back
     * landed between the SELECT and the UPDATE is skipped by the state door
     * instead of clobbering a settled status.
     */
    public int sweepWithWindow(int minutes) {
        if (minutes <= 0) {
            log.warn("生成任务停滞窗口非法（minutes={}）：本轮拒绝清扫，保持全部在途态", minutes);
            return 0;
        }
        List<Long> staleIds = jdbc.queryForList(
                "SELECT id FROM tp_ai_task WHERE status = :running "
                        + "AND updated_at < now() - make_interval(mins => :minutes)",
                new MapSqlParameterSource()
                        .addValue("running", STATUS_RUNNING)
                        .addValue("minutes", minutes),
                Long.class);
        int settled = 0;
        for (Long id : staleIds) {
            try {
                aiTaskService.markFailed(id, STALL_ERROR_CODE, STALL_ERROR_TEXT);
                settled++;
            } catch (ServiceException e) {
                // Concurrent write-back settled the row first (done/failed/
                // cancelled): honest skip, never a second status path.
                log.info("生成任务 #{} 在清扫间隙已被回写，跳过（{}）", id, e.getMessage());
            }
        }
        return settled;
    }

    /**
     * K8 value -&gt; stall minutes, fail-closed: anything unparseable falls back
     * to the seeded default so a mistyped key can never widen the blast to
     * "fail all in-flight generations" (negative/zero pass through for
     * sweepWithWindow's refuse branch — an explicit "-1" reads as
     * "keep everything", honored).
     */
    static int parseStallMinutes(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_STALL_MINUTES;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_STALL_MINUTES;
        }
    }
}
