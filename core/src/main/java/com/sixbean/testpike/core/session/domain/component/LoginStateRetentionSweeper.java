package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention sweep for the login-state ledger the owner
 * loop that keeps the seeded {@code session.retention-days} key from being a
 * knob nobody turns (the seeded-key-without-owner-loop shape named in the AV design doc).
 * <p>Posture notes:
 * <ul>
 * <li>window read fresh from the K8 key on every run (hot update), cron
 * catch separated from the business entry — same idiom as
 * MockHitRetentionSweeper/ReportRetentionScheduler;</li>
 * <li>only disabled rows (status=0) past the window are swept; enabled rows
 * of any age stay untouched — retention covers failed entries, not the
 * whole ledger;</li>
 * <li>vault ciphertext is purged only for capture-produced rows
 * (source_kind=3) that no remaining ledger row references — manual and
 * legacy vault rows are out of scope by construction;</li>
 * <li>a non-positive window refuses the sweep instead of purging everything
 * (a mistyped 0 must never mean "delete the ledger").</li>
 * </ul>
 * S3 snapshot objects are not written by the ledger path yet; when the
 * capture storage_state writer lands, the sweep must pair up the object
 * removal here (R57 creation-pairing obligation, booked in .
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class LoginStateRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(LoginStateRetentionSweeper.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SessionGovernanceKeys governanceKeys;
    private final JobRunner jobRunner;

    public LoginStateRetentionSweeper(NamedParameterJdbcTemplate jdbc,
                                      SessionGovernanceKeys governanceKeys,
                                      JobRunner jobRunner) {
        this.jdbc = jdbc;
        this.governanceKeys = governanceKeys;
        this.jobRunner = jobRunner;
    }

    /** Daily sweep of disabled ledger rows past the retention window (04:30, off-peak). */
    @SchedulerLock(name = "loginStateRetentionSweep", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${testpike.session.state-retention-cron:0 30 4 * * *}")
    public void sweep() {
        try {
            int removed = purgeExpired();
            if (removed > 0) {
                log.info("登录态失效条目保留清理：{} 行超窗已删除", removed);
            }
        } catch (Exception e) {
            log.warn("登录态失效条目保留清理失败（下轮自动重试，幂等续清）: {}", e.getMessage());
            jobRunner.alert("loginStateRetentionSweep", e);
        }
    }

    /**
     * One purge round against the freshly read K8 window (business entry
     * shared by the cron catch, the run-now picker and the tests).
     *
     * @return ledger rows deleted this round
     */
    public int purgeExpired() {
        return sweepWithWindow(governanceKeys.retentionDays());
    }

    /**
     * Delete disabled rows older than {@code days}, then unreferenced
     * capture-produced vault rows. Non-positive windows are refused
     * (keep-everything semantics — never purge-everything).
     */
    public int sweepWithWindow(int days) {
        if (days <= 0) {
            log.warn("登录态保留窗非法（days={}）：本轮拒绝清理，保持全量", days);
            return 0;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("days", days);
        int removed = jdbc.update("DELETE FROM tp_login_state "
                        + "WHERE status = 0 AND updated_at < now() - make_interval(days => :days)", params);
        int purged = jdbc.update("DELETE FROM tp_credential c "
                + "WHERE c.source_kind = 3 AND NOT EXISTS ("
                + "SELECT 1 FROM tp_login_state l WHERE l.credential_id = c.id)",
                new MapSqlParameterSource());
        if (purged > 0) {
            log.info("登录态失效条目保留清理：{} 条采集密文行失去引用已随窗删除", purged);
        }
        return removed;
    }
}
