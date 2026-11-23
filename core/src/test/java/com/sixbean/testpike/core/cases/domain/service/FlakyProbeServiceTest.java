package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.FlakyProbeSweepResult;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flaky re-probe sweep service test .
 * <p>Covers the ledger contract of the closed loop: opening one session per quarantined
 * case (idempotent across overlapping sweeps thanks to the partial unique index), closing
 * sessions whose case is no longer quarantined, folding only post-quarantine terminal
 * results through the last_task_id cursor (replaying the same set writes nothing), raising
 * a restore proposal with one in-site notification to the assignee at the threshold
 * (ledger-only when there is no assignee), withdrawing a pending proposal when a new
 * failure lands, and the disabled-key short-circuit that performs zero writes.
 * <p>Real dev database, no mocks (strict testing standard). Fixtures are self-created and
 * cleaned by id; factory baseline tables are never truncated (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class FlakyProbeServiceTest {

    private static final String KEY_ENABLED = "ai.flaky.probe.enabled";
    private static final String KEY_INTERVAL = "ai.flaky.probe.interval-minutes";
    private static final String KEY_GREEN = "ai.flaky.probe.green-count";

    @Autowired
    private FlakyProbeService flakyProbeService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private final String userPrefix = "fpuser" + UUID.randomUUID().toString().substring(0, 8) + "_";

    @BeforeEach
    void createProject() {
        projectId = projectService.create(1L, new ProjectCreateInput(
                "FP回探项目", "fp" + UUID.randomUUID().toString().substring(0, 8), "zh-CN", "P1", null));
        setKey(KEY_ENABLED, "true", "bool");
        setKey(KEY_INTERVAL, "30", "int");
        setKey(KEY_GREEN, "5", "int");
    }

    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM tp_flaky_probe WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_script WHERE case_id IN"
                + " (SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_notification WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE ?)", userPrefix + "%");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE ?", userPrefix + "%");
        setKey(KEY_ENABLED, "true", "bool");
        setKey(KEY_GREEN, "5", "int");
        setKey(KEY_INTERVAL, "30", "int");
    }

    // ---- helpers -------------------------------------------------------------

    /** Insert a quarantined case; assigneeId may be null (never notified externally). */
    private long quarantineCase(Long assigneeId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_test_case (case_no, project_id, title, status, flaky,
                                          flaky_reason, flaky_marked_at, assignee_id)
                VALUES (?, ?, '回探夹具用例', 3, 1, '连续结果翻转', now(), ?)
                RETURNING id
                """, Long.class, "TC-FP-" + UUID.randomUUID().toString().substring(0, 12), projectId, assigneeId);
    }

    private void addRun(long caseId, int status) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, started_at, finished_at)
                VALUES (?, ?, ?, now(), now())
                """, projectId, caseId, status);
    }

    /** Quarantined case with an explicit status (dispatch tests keep it off the passed
     *  status so the shared create channel's J2 flip never fires and the row stays
     *  byte-identical - the ledger must add no tp_test_case writes of its own). */
    private long quarantineCaseWithStatus(int status) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_test_case (case_no, project_id, title, status, flaky,
                                          flaky_reason, flaky_marked_at)
                VALUES (?, ?, '回探派发夹具用例', ?, 1, '连续结果翻转', now())
                RETURNING id
                """, Long.class, "TC-FPD-" + UUID.randomUUID().toString().substring(0, 12),
                projectId, status);
    }

    /** Script row for the case: status 1=usable, 2=pending-update (must not dispatch).
     *  Single-key action JSON keeps the jsonb canonical text order-stable for assertions. */
    private void addScript(long caseId, int status) {
        jdbcTemplate.update("""
                INSERT INTO tp_script (case_id, schema_version, action_json, status)
                VALUES (?, 'v2', ?::jsonb, ?)
                """, caseId, "{\"probeFixture\": true}", status);
    }

    /** Dev-type environment for the project (the dispatcher's env resolution picks it). */
    private void addEnv() {
        jdbcTemplate.update("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url)
                VALUES (?, 'fp-probe-dev', 1, 'http://fp-probe.invalid')
                """, projectId);
    }

    /** Make the open session due right now (a due round dispatches, a fresh one waits). */
    private void backdateNextProbe(long caseId) {
        jdbcTemplate.update("""
                UPDATE tp_flaky_probe SET next_probe_at = now() - interval '1 minute'
                WHERE case_id = ? AND state IN (0, 1)
                """, caseId);
    }

    private int pendingTaskCount(long caseId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE case_id = ? AND status IN (1, 2)",
                Integer.class, caseId);
        return n == null ? 0 : n;
    }

    /** Identity snapshot proving the dispatch round wrote nothing to the case row. */
    private String caseRowSnapshot(long caseId) {
        return jdbcTemplate.queryForObject("""
                SELECT concat_ws('|', status, version, flaky,
                                 coalesce(flaky_marked_at::text, ''), updated_at::text)
                FROM tp_test_case WHERE id = ?
                """, String.class, caseId);
    }

    /** A run that finished an hour before the case was quarantined (stale evidence). */
    private void addRunBeforeTheMark(long caseId, int status) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, started_at, finished_at)
                VALUES (?, ?, ?, now() - interval '2 hours', now() - interval '1 hour')
                """, projectId, caseId, status);
    }

    /** The one open (not closed) session row of a case, never zero in these tests. */
    private OpenSession openSession(long caseId) {
        List<OpenSession> rows = jdbcTemplate.query("""
                SELECT id, green_streak, probed_runs, last_task_id, state, proposed_at, closed_at
                FROM tp_flaky_probe WHERE case_id = ? AND state IN (0, 1)
                """, (java.sql.ResultSet rs, int i) -> new OpenSession(
                        rs.getLong("id"), rs.getInt("green_streak"), rs.getInt("probed_runs"),
                        rs.getLong("last_task_id"), rs.getInt("state"),
                        rs.getTimestamp("proposed_at"), rs.getTimestamp("closed_at")), caseId);
        assertEquals(1, rows.size(), "expected exactly one open probe session for the case");
        return rows.get(0);
    }

    private int probeRowCount(long caseId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_flaky_probe WHERE case_id = ?", Integer.class, caseId);
        return n == null ? 0 : n;
    }

    private int notificationCount(long caseId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key = ?",
                Integer.class, "flaky-probe:" + caseId);
        return n == null ? 0 : n;
    }

    private long ensureAssigneeUser() {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, role, status)
                VALUES (?, NULL, 1, 1) RETURNING id
                """, Long.class, userPrefix + UUID.randomUUID().toString().substring(0, 6));
        return id;
    }

    private void setKey(String key, String value, String type) {
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        ConfigInput input = new ConfigInput(key, value, "系统", type, 0,
                "不稳定用例回探参数（夹具写入，测后复位）");
        if (id == null) {
            configService.create(1L, input);
        } else {
            configService.update(1L, id, input);
        }
    }

    /** Shape of the single open session row the ledger contract asserts on. */
    record OpenSession(long id, int greenStreak, int probedRuns, long lastTaskId,
                       int state, Timestamp proposedAt, Timestamp closedAt) {
    }

    // ---- tests ---------------------------------------------------------------

    @Test
    void openingASessionIsIdempotentAcrossSweeps() {
        long caseId = quarantineCase(null);
        FlakyProbeSweepResult first = flakyProbeService.sweep();
        assertEquals(1, first.opened());
        OpenSession s = openSession(caseId);
        assertEquals(0, s.state());
        assertNotNull(s.id());

        FlakyProbeSweepResult second = flakyProbeService.sweep();
        assertEquals(0, second.opened());
        assertEquals(1, probeRowCount(caseId), "a second sweep must not open a duplicate session");
    }

    @Test
    void aSessionClosesWhenTheCaseIsNoLongerQuarantined() {
        long caseId = quarantineCase(null);
        flakyProbeService.sweep();
        jdbcTemplate.update("UPDATE tp_test_case SET flaky = 0 WHERE id = ?", caseId);

        FlakyProbeSweepResult result = flakyProbeService.sweep();
        assertEquals(1, result.closed());
        Integer open = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_flaky_probe WHERE case_id = ? AND state IN (0, 1)",
                Integer.class, caseId);
        assertEquals(0, open);
        Timestamp closedAt = jdbcTemplate.queryForObject(
                "SELECT closed_at FROM tp_flaky_probe WHERE case_id = ? AND state = 2",
                Timestamp.class, caseId);
        assertNotNull(closedAt);
    }

    @Test
    void foldingCountsOnlyPostQuarantineTerminalRunsAndReplayWritesNothing() {
        long caseId = quarantineCase(null);
        addRunBeforeTheMark(caseId, 3); // finished before quarantine: the cursor must skip it
        flakyProbeService.sweep();
        assertEquals(0, openSession(caseId).greenStreak(),
                "pre-quarantine results must not count toward the streak");

        addRun(caseId, 3);
        addRun(caseId, 3);
        FlakyProbeSweepResult folded = flakyProbeService.sweep();
        assertEquals(1, folded.folded());
        OpenSession after = openSession(caseId);
        assertEquals(2, after.greenStreak());
        assertEquals(2, after.probedRuns());

        Timestamp updatedBefore = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tp_flaky_probe WHERE id = ?", Timestamp.class, after.id());
        // An in-flight (status 2) run is not an outcome yet and must not move the streak.
        addRun(caseId, 2);
        FlakyProbeSweepResult replay = flakyProbeService.sweep();
        assertEquals(0, replay.folded(), "a sweep without new terminal results folds nothing");
        OpenSession same = openSession(caseId);
        assertEquals(2, same.greenStreak());
        Timestamp updatedAfter = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tp_flaky_probe WHERE id = ?", Timestamp.class, after.id());
        assertEquals(updatedBefore, updatedAfter,
                "replaying the same result set must write nothing (zero-write idempotency)");
    }

    @Test
    void reachingTheThresholdProposesRestoreAndNotifiesTheAssigneeOnce() {
        setKey(KEY_GREEN, "2", "int");
        long assignee = ensureAssigneeUser();
        long caseId = quarantineCase(assignee);
        addRun(caseId, 3);
        addRun(caseId, 3);

        FlakyProbeSweepResult result = flakyProbeService.sweep();
        assertEquals(1, result.proposed());
        OpenSession s = openSession(caseId);
        assertEquals(1, s.state(), "threshold reached: proposal pending human confirmation");
        assertNotNull(s.proposedAt());
        assertEquals(1, notificationCount(caseId));
        Long notifiedUser = jdbcTemplate.queryForObject(
                "SELECT user_id FROM tp_notification WHERE dedup_key = ?", Long.class,
                "flaky-probe:" + caseId);
        assertEquals(assignee, notifiedUser);

        // A second crossing without new results proposes nothing and never re-notifies.
        FlakyProbeSweepResult second = flakyProbeService.sweep();
        assertEquals(0, second.proposed());
        assertEquals(1, notificationCount(caseId));
    }

    @Test
    void aProposalWithoutAssigneeKeepsTheLedgerButSendsNothing() {
        setKey(KEY_GREEN, "2", "int");
        long caseId = quarantineCase(null);
        addRun(caseId, 3);
        addRun(caseId, 3);

        FlakyProbeSweepResult result = flakyProbeService.sweep();
        assertEquals(1, result.proposed(), "the ledger records the proposal even with no recipient");
        assertEquals(1, openSession(caseId).state());
        assertEquals(0, notificationCount(caseId));
    }

    @Test
    void aNewFailureWithdrawsThePendingProposal() {
        setKey(KEY_GREEN, "2", "int");
        long caseId = quarantineCase(null);
        addRun(caseId, 3);
        addRun(caseId, 3);
        flakyProbeService.sweep();
        assertEquals(1, openSession(caseId).state());

        addRun(caseId, 4); // proved unstable again: the stale suggestion must not stay confirmable
        FlakyProbeSweepResult result = flakyProbeService.sweep();
        assertEquals(1, result.withdrew());
        OpenSession s = openSession(caseId);
        assertEquals(0, s.state(), "withdraw returns the session to probing");
        assertNull(s.proposedAt());
        assertEquals(0, s.greenStreak());
    }

    @Test
    void theSweepShortCircuitsWithZeroWritesWhenProbeKeyIsDisabled() {
        setKey(KEY_ENABLED, "false", "bool");
        long caseId = quarantineCase(null);
        addRun(caseId, 3);

        FlakyProbeSweepResult result = flakyProbeService.sweep();
        assertTrue(result.disabled());
        assertEquals(0, result.opened() + result.closed() + result.folded());
        assertEquals(0, probeRowCount(caseId), "disabled sweep opens nothing");
    }

    // ---- dispatch stage (due sessions probe through the shared channel) ----

    @Test
    void dueSessionDispatchesOneProbeRunAndPacesTheNextOne() {
        long caseId = quarantineCaseWithStatus(7);
        addScript(caseId, 1);
        addEnv();
        flakyProbeService.sweep(); // opens the session; next_probe_at sits in the future
        assertEquals(0, flakyProbeService.sweep().dispatched(),
                "a fresh session never dispatches before it is due");
        assertEquals(0, pendingTaskCount(caseId));

        backdateNextProbe(caseId);
        String before = caseRowSnapshot(caseId);
        FlakyProbeSweepResult result = flakyProbeService.sweep();
        assertEquals(1, result.dispatched());
        assertEquals(0, result.probeSkipped());
        assertEquals(1, pendingTaskCount(caseId),
                "the due round must hand exactly one run to the shared execution channel");
        assertEquals("http://fp-probe.invalid", jdbcTemplate.queryForObject("""
                SELECT env_base_url FROM tp_exec_task
                WHERE case_id = ? ORDER BY id DESC LIMIT 1
                """, String.class, caseId),
                "the run snapshot resolves through the project environment, not a raw URL");
        assertEquals("{\"probeFixture\": true}", jdbcTemplate.queryForObject("""
                SELECT action_json FROM tp_exec_task
                WHERE case_id = ? ORDER BY id DESC LIMIT 1
                """, String.class, caseId), "the script snapshot rides the dispatch unchanged");
        assertEquals(before, caseRowSnapshot(caseId),
                "probe dispatch performs no tp_test_case writes of its own");

        // One per interval: still not due, nothing new dispatches.
        FlakyProbeSweepResult paced = flakyProbeService.sweep();
        assertEquals(0, paced.dispatched() + paced.probeSkipped());
        assertEquals(1, pendingTaskCount(caseId));

        // Due again while the previous run is still in flight: skip honestly, no duplicate.
        backdateNextProbe(caseId);
        FlakyProbeSweepResult inflight = flakyProbeService.sweep();
        assertEquals(0, inflight.dispatched());
        assertEquals(1, inflight.probeSkipped());
        assertEquals(1, pendingTaskCount(caseId));
    }

    @Test
    void dispatchSkipsUnrunnableCasesHonestlyAndPacesTheRetry() {
        long noScript = quarantineCaseWithStatus(7);
        long staleScript = quarantineCaseWithStatus(7);
        addScript(staleScript, 2); // pending-update script: not fit to run
        addEnv();
        flakyProbeService.sweep(); // open both sessions
        backdateNextProbe(noScript);
        backdateNextProbe(staleScript);

        FlakyProbeSweepResult result = flakyProbeService.sweep();
        assertEquals(0, result.dispatched());
        assertEquals(2, result.probeSkipped());
        assertEquals(0, pendingTaskCount(noScript) + pendingTaskCount(staleScript));
        for (long caseId : new long[]{noScript, staleScript}) {
            Timestamp next = jdbcTemplate.queryForObject(
                    "SELECT next_probe_at FROM tp_flaky_probe WHERE case_id = ?",
                    Timestamp.class, caseId);
            assertTrue(next.after(Timestamp.valueOf(LocalDateTime.now())),
                    "a skipped dispatch still paces its retry on the probe interval (no hot loop)");
        }
    }

    @Test
    void dispatchSkipsWhenTheProjectHasNoEnvironment() {
        long caseId = quarantineCaseWithStatus(7);
        addScript(caseId, 1); // runnable script, but the project has no environment at all
        flakyProbeService.sweep(); // open
        backdateNextProbe(caseId);

        FlakyProbeSweepResult result = flakyProbeService.sweep();
        assertEquals(0, result.dispatched());
        assertEquals(1, result.probeSkipped());
        assertEquals(0, pendingTaskCount(caseId));
    }
}
