package com.sixbean.testpike.core.script.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackInput;
import com.sixbean.testpike.core.script.domain.service.ScriptWritebackService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.ScriptWritebackDrafts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Platform re-validation of landed write-backs ( grade-3 owner, real DB
 * and real Redis, no mocks).
 * <p>Contract pinned here:
 * (1) a landed write-back automatically schedules one platform trial-run task
 * over the single execution-task creation path (test environment only —
 * production never auto-executes), leaving the case grade untouched until the
 * task settles;
 * (2) a passing settle raises evidence_grade to 3 and writes the reproof
 * artifact into the audit trail the review card reads;
 * (3) a failing settle keeps the grade and only records the failure — no
 * exception escapes toward the result relay chain;
 * (4) the ledger is consumed exactly once: replays are inert and ordinary
 * (non-reproof) trial tasks have zero side effects;
 * (5) the validity window is hot-configurable via the shared TTL policy.
 * <p>Bidirectional pairing: every guard (absent ledger / no test environment)
 * is asserted next to its live counterpart in the same suite, so removing a
 * guard cannot leave the suite green.
 */
@SpringBootTest(classes = CoreApplication.class)
class ScriptWritebackPlatformReproofTest {

    @Autowired
    private ScriptWritebackService scriptWritebackService;
    @Autowired
    private ScriptWritebackPlatformReproof platformReproof;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private final List<Long> trackedProjects = new CopyOnWriteArrayList<>();
    private final List<Long> trackedTasks = new CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (Long taskId : trackedTasks) {
            redis.delete(ScriptWritebackPlatformReproof.ledgerKey(taskId));
        }
        trackedTasks.clear();
        configCacheHolder.remove(ScriptWritebackPlatformReproof.TTL_KEY);
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "reproof project " + code, code);
    }

    private long insertEnv(long projectId, int envType, String name) {
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url)"
                        + " VALUES (?, ?, ?, ?)",
                projectId, name, envType, "https://" + name + ".reproof.invalid");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND name = ?",
                Long.class, projectId, name);
    }

    private long landedWriteback(long caseId) {
        return scriptWritebackService.writeback(new ScriptWritebackInput(caseId, "v2",
                ScriptWritebackDrafts.ACTION_JSON, ScriptWritebackDrafts.evidence(), null, 1L)).caseId();
    }

    private long latestTrialTaskId(long caseId) {
        long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE case_id = ? AND batch_id IS NULL"
                        + " ORDER BY id DESC LIMIT 1", Long.class, caseId);
        trackedTasks.add(taskId);
        return taskId;
    }

    private int grade(long caseId) {
        Integer g = jdbcTemplate.queryForObject(
                "SELECT evidence_grade FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        return g == null ? -1 : g;
    }

    private int reproofTrailCount(long caseId, String result) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_id = ? AND action = ?"
                        + " AND after_json::text LIKE ?",
                Integer.class, String.valueOf(caseId),
                ScriptWritebackPlatformReproof.ACTION_REPROOF, "%\"" + result + "\"%");
        return n == null ? 0 : n;
    }

    @Test
    void landedWritebackSchedulesTrialAndPassRaisesGradeThree() {
        long projectId = createProject("rp-pass");
        insertEnv(projectId, 1, "rp-pass-test");
        long caseId = ScriptWritebackDrafts.createCase(caseService, projectId);
        landedWriteback(caseId);

        assertEquals(2, grade(caseId), "grade waits at evidence-on-file until the platform settles");
        long taskId = latestTrialTaskId(caseId);
        assertEquals(1, taskStatus(taskId), "scheduled trial task waits for dispatch");
        assertNotNull(redis.opsForValue().get(ScriptWritebackPlatformReproof.ledgerKey(taskId)),
                "the reproof ledger registers the scheduled task");

        platformReproof.onResultSubmitted(execTaskService.get(taskId), "pass");
        assertEquals(3, grade(caseId));
        assertEquals(1, reproofTrailCount(caseId, "pass"), "reproof artifact lands on the review trail");
        assertNull(redis.opsForValue().get(ScriptWritebackPlatformReproof.ledgerKey(taskId)),
                "the ledger is consumed at the settle point");

        // Replay of the same task with the opposite verdict stays inert.
        platformReproof.onResultSubmitted(execTaskService.get(taskId), "fail");
        assertEquals(3, grade(caseId), "one-shot ledger: no second settle rewrites the grade");
        assertEquals(0, reproofTrailCount(caseId, "fail"));
    }

    @Test
    void failingTrialKeepsGradeAndOnlyRecordsTheFailure() {
        long projectId = createProject("rp-fail");
        insertEnv(projectId, 1, "rp-fail-test");
        long caseId = ScriptWritebackDrafts.createCase(caseService, projectId);
        landedWriteback(caseId);
        long taskId = latestTrialTaskId(caseId);

        platformReproof.onResultSubmitted(execTaskService.get(taskId), "fail");

        assertEquals(2, grade(caseId), "a failed reproof never downgrades or upgrades the grade");
        assertEquals(1, reproofTrailCount(caseId, "fail"), "the failure keeps an audit trail");
        assertNull(redis.opsForValue().get(ScriptWritebackPlatformReproof.ledgerKey(taskId)));
    }

    @Test
    void ordinaryTrialTasksNeverTouchTheReproofGrade() {
        long projectId = createProject("rp-plain");
        long envId = insertEnv(projectId, 1, "rp-plain-test");
        long caseId = ScriptWritebackDrafts.createCase(caseService, projectId);
        long taskId = execTaskService.create(new ExecTaskCreateInput(projectId, caseId, "v2",
                ScriptWritebackDrafts.ACTION_JSON, envId, null));
        trackedTasks.add(taskId);

        platformReproof.onResultSubmitted(execTaskService.get(taskId), "pass");

        assertEquals(0, grade(caseId), "an unregistered task is not a reproof: grade untouched");
        assertEquals(0, reproofTrailCount(caseId, "pass"));
    }

    @Test
    void projectWithoutTestEnvironmentSkipsSchedulingWithoutBlocking() {
        long projectId = createProject("rp-nopro");
        insertEnv(projectId, 2, "rp-nopro-prod");
        long caseId = ScriptWritebackDrafts.createCase(caseService, projectId);

        landedWriteback(caseId); // must not throw: reproof is best-effort by design

        assertEquals(2, grade(caseId));
        Integer tasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE case_id = ?", Integer.class, caseId);
        assertEquals(0, tasks, "production environments never schedule an automatic trial");
        assertTrue(reproofTrailCount(caseId, "skipped") >= 1,
                "the skip is recorded instead of silently lost");
    }

    @Test
    void reproofWindowIsHotConfigurable() {
        long projectId = createProject("rp-ttl");
        insertEnv(projectId, 1, "rp-ttl-test");
        long caseId = ScriptWritebackDrafts.createCase(caseService, projectId);
        configCacheHolder.put(ScriptWritebackPlatformReproof.TTL_KEY, "3600");

        landedWriteback(caseId);
        long taskId = latestTrialTaskId(caseId);
        Long ttl = redis.getExpire(ScriptWritebackPlatformReproof.ledgerKey(taskId), TimeUnit.SECONDS);
        assertTrue(ttl != null && ttl > 3000 && ttl <= 3600,
                "configured window is read live at ledger write time, got " + ttl);

        // Illegal shapes fall back to the shipped default, never to zero.
        configCacheHolder.put(ScriptWritebackPlatformReproof.TTL_KEY, "not-a-number");
        assertEquals(ScriptWritebackPlatformReproof.DEFAULT_TTL_SECONDS, platformReproof.ttlSeconds());
    }

    private int taskStatus(long taskId) {
        Integer s = jdbcTemplate.queryForObject("SELECT status FROM tp_exec_task WHERE id = ?",
                Integer.class, taskId);
        return s == null ? -1 : s;
    }
}
