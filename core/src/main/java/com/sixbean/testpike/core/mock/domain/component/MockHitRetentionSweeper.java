package com.sixbean.testpike.core.mock.domain.component;

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
 * Retention sweep for the mock hit ledger lights up the
 * {@code mock.hit-retention-days} K8 key the shell batch seeded with "the
 * shell only defines the semantics"; before this class nothing read the key,
 * so tp_mock_hit_log grew without bound).
 * <p>Posture notes:
 * <ul>
 * <li>heartbeat/decision separation: the cron tick is fixed (default daily
 * 04:00, key {@code testpike.mock.hit-retention-cron}); the real moment
 * is each row's own {@code hit_at} against the window read fresh from
 * the K8 key on every run (hot-update, same pattern as the retention
 * domain's config reads) — MockSpaceExpirySweeper/ReportRetentionScheduler
 * precedent;</li>
 * <li>fail-closed parsing: blank/garbage/absent value falls back to the
 * seeded default 30; a non-positive window refuses the sweep instead of
 * purging everything (a mistyped "0" must never mean "delete history");</li>
 * <li>idempotent DELETE (no FKs hang off this ledger; inspector pages read
 * the bounded window, so removing older rows breaks no reference);</li>
 * <li>the business entry is shared with the run-now picker and the tests —
 * the @Scheduled method only adds catch + JobRunner alert.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class MockHitRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(MockHitRetentionSweeper.class);

    /** Seeded default of mock.hit-retention-days (V228); fail-closed fallback for unreadable values. */
    static final int DEFAULT_RETENTION_DAYS = 30;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final JobRunner jobRunner;

    public MockHitRetentionSweeper(NamedParameterJdbcTemplate jdbc,
                                   ConfigService configService,
                                   JobRunner jobRunner) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.jobRunner = jobRunner;
    }

    /** Daily sweep of hit rows past the retention window (default 04:00, off-peak). */
    @SchedulerLock(name = "mockHitSweep", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${testpike.mock.hit-retention-cron:0 0 4 * * *}")
    public void sweep() {
        try {
            int deleted = purgeExpiredHits();
            if (deleted > 0) {
                log.info("Mock 命中流水保留清理：{} 行超窗已删除", deleted);
            }
        } catch (Exception e) {
            log.warn("Mock 命中流水保留清理失败（下轮自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("mockHitSweep", e);
        }
    }

    /**
     * One purge round against the freshly read K8 window (business entry
     * shared by the cron catch, the run-now picker and the tests).
     *
     * @return hit rows deleted this round
     */
    public int purgeExpiredHits() {
        int days = parseRetentionDays(configService.getValue("mock.hit-retention-days",
                String.valueOf(DEFAULT_RETENTION_DAYS)));
        return sweepWithWindow(days);
    }

    /**
     * Delete ledger rows older than {@code days}. Non-positive windows are
     * refused (keep-everything semantics — never purge-everything).
     */
    public int sweepWithWindow(int days) {
        if (days <= 0) {
            log.warn("Mock 命中流水保留窗非法（days={}）：本轮拒绝清理，保持全量", days);
            return 0;
        }
        return jdbc.update("DELETE FROM tp_mock_hit_log "
                        + "WHERE hit_at < now() - make_interval(days => :days)",
                new MapSqlParameterSource("days", days));
    }

    /**
     * K8 value -> window days, fail-closed: anything unparseable falls back
     * to the seeded default so a mistyped key can never widen the blast to
     * "all history" (negative/zero are passed through for sweepWithWindow's
     * refuse branch — an explicit "-1" reads as "keep everything", honored).
     */
    static int parseRetentionDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_RETENTION_DAYS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_RETENTION_DAYS;
        }
    }
}
