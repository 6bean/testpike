package com.sixbean.testpike.core.cases.domain.service.impl;

import com.sixbean.testpike.core.cases.api.dto.FlakyProbeDueSession;
import com.sixbean.testpike.core.cases.api.dto.FlakyProbeSession;
import com.sixbean.testpike.core.cases.api.dto.FlakyProbeSweepResult;
import com.sixbean.testpike.core.cases.api.dto.FlakyRestoreProposal;
import com.sixbean.testpike.core.cases.domain.component.FlakyProbeCalculator;
import com.sixbean.testpike.core.cases.domain.component.FlakyProbeDecision;
import com.sixbean.testpike.core.cases.domain.service.FlakyProbeService;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Flaky re-probe ledger sweep .
 * <p>One round over {@code tp_flaky_probe}: close sessions whose case left quarantine,
 * open one session per quarantined case (partial unique index keeps it idempotent across
 * overlapping runs and instances), fold post-quarantine terminal results through the
 * per-session {@code last_task_id} cursor, and drive the proposal state through the pure
 * {@link FlakyProbeCalculator} (same five-consistent-results bar as the daily detection,
 * never a second rule).
 * <p>Guarantees pinned by FlakyProbeServiceTest:
 * <ul>
 * <li>replaying the same result set writes nothing (cursor consumes no new rows, the
 * session row keeps its updated_at - collection editors rely on this for R28-style
 * idempotency of the loop);</li>
 * <li>the door is one-directional - reaching the threshold only records the proposal and
 * notifies the assignee; clearing tp_test_case.flaky stays a human confirmation on
 * the cases page, and a new failure withdraws the stale proposal instead of leaving
 * it confirmable;</li>
 * <li>thresholds/interval/switch are hot-updatable keys read every round through
 * {@link SystemConfigReader}, no hard-coded values.</li>
 * </ul>
 * <p>The disabled short-circuit performs zero writes and returns disabled=true, so the
 * scheduler and any future run-now path report honestly instead of pretending a round ran.
 * <p>segment 2c adds the dispatch stage: a due session hands exactly one re-probe run to
 * the shared {@link ExecTaskService#create} channel (production guard, blacklist and env
 * snapshot stay enforced there, never duplicated); in-flight cases and unrunnable ones
 * (missing/pending-update script, no environment, channel refusal) skip honestly with the
 * retry paced on the same interval, and the ledger performs no tp_test_case writes of
 * its own (the daily-detection semantics of J1 are untouched).
 */
@Service
public class FlakyProbeServiceImpl implements FlakyProbeService {

    static final String KEY_ENABLED = "ai.flaky.probe.enabled";
    static final String KEY_INTERVAL_MINUTES = "ai.flaky.probe.interval-minutes";
    static final String KEY_GREEN_COUNT = "ai.flaky.probe.green-count";
    private static final int DEFAULT_INTERVAL_MINUTES = 30;
    private static final int DEFAULT_GREEN_COUNT = 5;
    /** In-site event type; unknown to the preference dictionary = always generated (bypass). */
    static final String EVENT_RESTORE_PROPOSE = "FLAKY_RESTORE_PROPOSE";

    private static final Logger log = LoggerFactory.getLogger(FlakyProbeServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final SystemConfigReader configReader;
    private final FlakyProbeCalculator calculator;
    private final NotificationService notificationService;
    private final ScriptService scriptService;
    private final ExecTaskService execTaskService;

    public FlakyProbeServiceImpl(JdbcTemplate jdbcTemplate, SystemConfigReader configReader,
                                 FlakyProbeCalculator calculator,
                                 NotificationService notificationService,
                                 ScriptService scriptService, ExecTaskService execTaskService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configReader = configReader;
        this.calculator = calculator;
        this.notificationService = notificationService;
        this.scriptService = scriptService;
        this.execTaskService = execTaskService;
    }

    @Override
    public FlakyProbeSweepResult sweep() {
        if (!configReader.getBoolean(KEY_ENABLED, true)) {
            // Switch off: the loop is honestly absent this round - no opens, no folds, no writes.
            return new FlakyProbeSweepResult(0, 0, 0, 0, 0, 0, 0, true);
        }
        int intervalMinutes = Math.max(1,
                configReader.getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES));
        int greenThreshold = configReader.getInt(KEY_GREEN_COUNT, DEFAULT_GREEN_COUNT);
        LocalDateTime now = LocalDateTime.now();
        Timestamp nextProbeAt = Timestamp.valueOf(now.plusMinutes(intervalMinutes));

        int closed = closeSessionsOfReleasedCases();
        int opened = openSessionsForQuarantinedCases(nextProbeAt);

        int folded = 0;
        int proposed = 0;
        int withdrew = 0;
        for (FlakyProbeSession session : queryOpenSessions()) {
            List<Integer> statuses = jdbcTemplate.queryForList("""
                    SELECT status FROM tp_exec_task
                    WHERE case_id = ? AND status IN (3, 4, 5) AND id > ?
                    ORDER BY id
                    """, Integer.class, session.caseId(), session.lastTaskId());
            FlakyProbeDecision decision = calculator.evaluate(
                    statuses, session.greenStreak(), session.state() == 1, greenThreshold);
            if (decision.terminalCount() == 0) {
                continue; // no new outcome: the replay path never touches the row
            }
            Long maxTaskId = jdbcTemplate.queryForObject("""
                    SELECT max(id) FROM tp_exec_task
                    WHERE case_id = ? AND status IN (3, 4, 5) AND id > ?
                    """, Long.class, session.caseId(), session.lastTaskId());
            boolean propose = decision.proposeRestore();
            boolean withdraw = decision.withdrewProposal();
            int newState = propose ? 1 : (withdraw ? 0 : session.state());
            Timestamp proposedAt = propose ? Timestamp.valueOf(now)
                    : (withdraw ? null : readProposedAt(session.id()));
            jdbcTemplate.update("""
                    UPDATE tp_flaky_probe
                    SET green_streak = ?, probed_runs = probed_runs + ?, last_task_id = ?,
                        state = ?, proposed_at = ?::timestamp, next_probe_at = ?, updated_at = now()
                    WHERE id = ?
                    """, decision.greenStreak(), decision.terminalCount(),
                    maxTaskId == null ? session.lastTaskId() : maxTaskId,
                    newState, proposedAt, nextProbeAt, session.id());
            folded++;
            if (propose) {
                proposed++;
                notifyRestoreProposal(session, decision.greenStreak(), greenThreshold);
            }
            if (withdraw) {
                withdrew++;
            }
        }

        // Dispatch stage : runs that came due go through the shared execution
        // channel; folding above may already have paced a session past its due mark,
        // so this query re-reads the ledger after the fold writes.
        int dispatched = 0;
        int probeSkipped = 0;
        for (FlakyProbeDueSession due : queryDueSessions(Timestamp.valueOf(now))) {
            Integer inFlight = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM tp_exec_task WHERE case_id = ? AND status IN (1, 2)
                    """, Integer.class, due.caseId());
            if (inFlight != null && inFlight > 0) {
                // The previous probe run has not reached a terminal state yet: one run
                // per interval, skip this round and pace the retry.
                paceProbeRetry(due.sessionId(), nextProbeAt);
                probeSkipped++;
                continue;
            }
            ScriptInfo script = scriptService.getByCaseId(due.caseId());
            if (script == null || script.actionJson() == null || script.actionJson().isBlank()) {
                log.info("Flaky 回探未派发（用例 {}）：暂无可执行脚本，台账保留，下轮回探间隔自动复查",
                        due.caseId());
                paceProbeRetry(due.sessionId(), nextProbeAt);
                probeSkipped++;
                continue;
            }
            if (script.status() == 2) {
                log.info("Flaky 回探未派发（用例 {}）：脚本处于待更新状态，重新生成后回探自动恢复",
                        due.caseId());
                paceProbeRetry(due.sessionId(), nextProbeAt);
                probeSkipped++;
                continue;
            }
            // Environment resolution mirrors the defect-rerun precedent: dev-type first,
            // then lowest env_type; the production guard and blacklist stay inside
            // ExecTaskService.create (single enforcement point, never bypassed here).
            Long envId = jdbcTemplate.query("""
                    SELECT id FROM tp_project_env WHERE project_id = ?
                    ORDER BY (env_type = 1) DESC, env_type, id LIMIT 1
                    """, rs -> rs.next() ? rs.getLong(1) : null, due.projectId());
            if (envId == null) {
                log.info("Flaky 回探未派发（用例 {}）：项目暂无可用执行环境，先在环境配置中添加后自动恢复",
                        due.caseId());
                paceProbeRetry(due.sessionId(), nextProbeAt);
                probeSkipped++;
                continue;
            }
            try {
                execTaskService.create(new ExecTaskCreateInput(due.projectId(), due.caseId(),
                        script.schemaVersion(), script.actionJson(), envId));
                paceProbeRetry(due.sessionId(), nextProbeAt);
                dispatched++;
            } catch (ServiceException e) {
                // Per-session refusal (guard/blacklist/browser matrix): honest skip with
                // the reason logged, the round never dies and the ledger stays pending.
                log.warn("Flaky 回探提交被执行链拒绝（用例 {}）：{}", due.caseId(), e.getMessage());
                paceProbeRetry(due.sessionId(), nextProbeAt);
                probeSkipped++;
            }
        }
        return new FlakyProbeSweepResult(opened, closed, folded, proposed, withdrew,
                dispatched, probeSkipped, false);
    }

    @Override
    public List<FlakyRestoreProposal> pendingProposals(long projectId) {
        // Read-only projection of state-1 sessions; the threshold is re-read here (not
        // stored per row) so a hot retune surfaces immediately on the cases page.
        int greenThreshold = configReader.getInt(KEY_GREEN_COUNT, DEFAULT_GREEN_COUNT);
        return jdbcTemplate.query("""
                SELECT p.case_id, c.case_no, COALESCE(c.title, '用例 #' || c.id) AS case_title,
                       p.green_streak, p.proposed_at
                FROM tp_flaky_probe p
                JOIN tp_test_case c ON c.id = p.case_id AND c.deleted = 0
                WHERE p.state = 1 AND p.project_id = ? AND c.flaky = 1
                ORDER BY p.proposed_at DESC, p.id
                """, (java.sql.ResultSet rs, int rowNum) -> new FlakyRestoreProposal(
                        rs.getLong("case_id"), rs.getString("case_no"), rs.getString("case_title"),
                        rs.getInt("green_streak"), greenThreshold,
                        rs.getTimestamp("proposed_at") == null
                                ? null : rs.getTimestamp("proposed_at").toLocalDateTime()),
                projectId);
    }

    /** Open sessions whose due mark has passed (dispatch candidates, oldest due first). */
    private List<FlakyProbeDueSession> queryDueSessions(Timestamp now) {
        return jdbcTemplate.query("""
                SELECT id, case_id, project_id FROM tp_flaky_probe
                WHERE state IN (0, 1) AND next_probe_at <= ?
                ORDER BY next_probe_at, id
                """, (java.sql.ResultSet rs, int rowNum) -> new FlakyProbeDueSession(
                        rs.getLong("id"), rs.getLong("case_id"), rs.getLong("project_id")), now);
    }

    /** Pacing single point: every due round (dispatched or skipped) reschedules the
     *  session one probe interval ahead, so nothing hot-loops on the cron cadence. */
    private void paceProbeRetry(long sessionId, Timestamp nextProbeAt) {
        jdbcTemplate.update("""
                UPDATE tp_flaky_probe SET next_probe_at = ?, updated_at = now() WHERE id = ?
                """, nextProbeAt, sessionId);
    }

    /** Sessions whose case is no longer quarantined finish as state 2, kept for history. */
    private int closeSessionsOfReleasedCases() {
        return jdbcTemplate.update("""
                UPDATE tp_flaky_probe p
                SET state = 2, closed_at = now(), updated_at = now()
                WHERE p.state IN (0, 1) AND NOT EXISTS (
                    SELECT 1 FROM tp_test_case c
                    WHERE c.id = p.case_id AND c.flaky = 1 AND c.deleted = 0)
                """);
    }

    /**
     * Opens one session per quarantined case without an open one. The cursor is seeded with
     * the newest terminal run finished at-or-before the quarantine mark, so only
     * post-quarantine evidence counts toward the streak while runs that landed between the
     * mark and the first sweep are still folded. ON CONFLICT on the partial unique index
     * makes overlapping rounds and multiple instances a no-op.
     */
    private int openSessionsForQuarantinedCases(Timestamp nextProbeAt) {
        return jdbcTemplate.update("""
                INSERT INTO tp_flaky_probe (project_id, case_id, base_version, marked_at,
                                            last_task_id, next_probe_at)
                SELECT c.project_id, c.id, c.version, COALESCE(c.flaky_marked_at, now()),
                       COALESCE((SELECT max(t.id) FROM tp_exec_task t
                                 WHERE t.case_id = c.id AND t.status IN (3, 4, 5)
                                   AND COALESCE(t.finished_at, t.created_at)
                                       <= COALESCE(c.flaky_marked_at, now())), 0),
                       ?
                FROM tp_test_case c
                WHERE c.flaky = 1 AND c.deleted = 0
                  AND NOT EXISTS (SELECT 1 FROM tp_flaky_probe p
                                  WHERE p.case_id = c.id AND p.state IN (0, 1))
                ON CONFLICT (case_id) WHERE state IN (0, 1) DO NOTHING
                """, nextProbeAt);
    }

    private List<FlakyProbeSession> queryOpenSessions() {
        return jdbcTemplate.query("""
                SELECT p.id, p.case_id, p.project_id, p.green_streak, p.last_task_id, p.state,
                       c.assignee_id, COALESCE(c.title, '用例 #' || c.id) AS case_title
                FROM tp_flaky_probe p
                JOIN tp_test_case c ON c.id = p.case_id AND c.deleted = 0
                WHERE p.state IN (0, 1)
                ORDER BY p.next_probe_at, p.id
                """, (java.sql.ResultSet rs, int rowNum) -> new FlakyProbeSession(
                        rs.getLong("id"), rs.getLong("case_id"), rs.getLong("project_id"),
                        rs.getInt("green_streak"), rs.getLong("last_task_id"),
                        rs.getInt("state"),
                        rs.getObject("assignee_id") == null ? null : rs.getLong("assignee_id"),
                        rs.getString("case_title")));
    }

    /** A pending session that neither proposes nor withdraws keeps its recorded proposal time. */
    private Timestamp readProposedAt(long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT proposed_at FROM tp_flaky_probe WHERE id = ?", Timestamp.class, sessionId);
    }

    /**
     * Raise-side notification: recipient is the case assignee, and with no assignee the
     * proposal exists in the ledger only (no broadcast guesswork). dedup_key pins at most
     * one notification per case, so overlapping rounds cannot spam the inbox.
     */
    private void notifyRestoreProposal(FlakyProbeSession session, int streak, int threshold) {
        if (session.assigneeId() == null) {
            log.info("Flaky 回探转正建议（用例 {}）达到门槛 {} 次连续通过，无被分配人，仅记账不外发",
                    session.caseId(), threshold);
            return;
        }
        notificationService.create(new NotificationCreateInput(
                session.assigneeId(), session.projectId(), EVENT_RESTORE_PROPOSE,
                "用例可解除隔离：连续通过 " + streak + " 次",
                "用例「" + session.caseTitle() + "」被隔离后已连续通过 " + streak
                        + " 次（达到 " + threshold + " 次门槛）。可到用例库确认后解除隔离，系统不会自动解除；"
                        + "期间若再次失败，本提示会自动撤回。",
                "flaky-probe:" + session.caseId()));
    }
}
