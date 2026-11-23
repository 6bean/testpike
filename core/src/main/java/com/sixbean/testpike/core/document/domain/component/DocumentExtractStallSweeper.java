package com.sixbean.testpike.core.document.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stall sweep for zombie "extracting" documents .
 * <p>Why this class exists: an F2 extraction rides tp_document.status=3 from
 * dispatch until ai-service writes back. If the consumer dies mid-flight
 * (service restart, message loss, killed LLM call) no one ever writes the row
 * back — the document is stuck on "extracting" and the state machine refuses
 * dispatch, so the user has no recovery path but manual DB surgery. This
 * sweeper reclaims rows whose updated_at passed the stall window
 * (K8 key ai.doc.extract-stall-minutes, seeded 30 = ai-service consume lease
 * 15min x 2 — a still-healthy run always touches the row or finishes inside
 * the first lease) back to status=1 with a human-readable extract_error.
 * <p>Posture notes (MockHitRetentionSweeper precedent):
 * <ul>
 * <li>heartbeat/decision separation: the cron tick fires every 2 minutes
 * (: factory seed raised from a ten-minute to a two-minute
 * cadence so reclaimed rows surface fast even without the click-path;
 * key {@code testpike.document.extract-stall-cron}); the real decision
 * point is each row's own updated_at against the window read fresh on
 * every run (hot-update);</li>
 * <li>fail-closed parsing: blank/garbage falls back to the seeded default 30;
 * a non-positive window refuses the sweep instead of resetting every
 * in-flight extraction (a mistyped "0" must never mean "requeue all");</li>
 * <li>conditional UPDATE (status=3 still at write time): a write-back that
 * lands between the SELECT count and this UPDATE wins — the sweeper only
 * ever touches rows still stuck at 3, never an already-settled one;</li>
 * <li>the business entry is shared with the run-now picker and the tests —
 * the @Scheduled method only adds catch + JobRunner alert.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentExtractStallSweeper {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractStallSweeper.class);

    /** K8 key holding the stall window in minutes (V293 seeds 30). */
    public static final String STALL_MINUTES_KEY = "ai.doc.extract-stall-minutes";

    /** Seeded default; fail-closed fallback for unreadable values. */
    static final int DEFAULT_STALL_MINUTES = 30;

    /** Human-readable error shown on the document card after reclaim (VARCHAR(500) safe). */
    static final String STALL_ERROR_TEXT = "提取任务疑似中断（长时间未回写），已恢复为可提取状态，请重试提取";

    /** tp_document status values touched by this sweep (01 v2.11 task-state bits). */
    private static final int STATUS_PARSE_OK = 1;
    private static final int STATUS_EXTRACTING = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final JobRunner jobRunner;

    public DocumentExtractStallSweeper(NamedParameterJdbcTemplate jdbc,
                                       ConfigService configService,
                                       JobRunner jobRunner) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.jobRunner = jobRunner;
    }

    /** Periodic reclaim of stalled extraction rows . */
    @SchedulerLock(name = "docExtractStallSweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${testpike.document.extract-stall-cron:0 */2 * * * *}")
    public void sweep() {
        try {
            int reclaimed = sweepStalled();
            if (reclaimed > 0) {
                log.info("提取中僵尸清扫：{} 篇超窗文档已回退可重试态", reclaimed);
            }
        } catch (Exception e) {
            log.warn("提取中僵尸清扫失败（下轮自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("docExtractStallSweep", e);
        }
    }

    /**
     * One reclaim round against the freshly read K8 window (business entry
     * shared by the cron catch, the run-now picker and the tests).
     *
     * @return document rows moved 3 -&gt; 1 this round
     */
    public int sweepStalled() {
        int minutes = parseStallMinutes(configService.getValue(STALL_MINUTES_KEY,
                String.valueOf(DEFAULT_STALL_MINUTES)));
        return sweepWithWindow(minutes);
    }

    /**
     * Reclaim status=3 rows idle for more than {@code minutes}. Non-positive
     * windows are refused (keep-everything semantics — never reset-everything).
     * The UPDATE re-asserts status=3 in its WHERE, so a concurrent write-back
     * (row already 4/1) settles zero rows here instead of clobbering state.
     */
    public int sweepWithWindow(int minutes) {
        if (minutes <= 0) {
            log.warn("提取停滞窗口非法（minutes={}）：本轮拒绝清扫，保持全部在途态", minutes);
            return 0;
        }
        return jdbc.update("UPDATE tp_document SET status = :ok, extract_error = :err, "
                        + "updated_at = now() "
                        + "WHERE status = :extracting "
                        + "AND updated_at < now() - make_interval(mins => :minutes)",
                new MapSqlParameterSource()
                        .addValue("ok", STATUS_PARSE_OK)
                        .addValue("err", STALL_ERROR_TEXT)
                        .addValue("extracting", STATUS_EXTRACTING)
                        .addValue("minutes", minutes));
    }

    /**
     * K8 value -&gt; stall minutes, fail-closed: anything unparseable falls back
     * to the seeded default so a mistyped key can never widen the blast to
     * "reset all in-flight extractions" (negative/zero pass through for
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
