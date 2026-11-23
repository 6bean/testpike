package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false",
        "testpike.prod-blacklist=*.prod-demo.com"})
class BatchTimeoutTest {

    private static final String CASE_TIMEOUT_KEY = "exec.case-timeout-minutes";
    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local/login\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private BatchService batchService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private ConfigService configService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigCacheHolder configCacheHolder;

    /** Fixed-name fixture project family (see FIXTURE_PROJECT_NAME_PREFIXES) owned by this suite. */
    private static final List<String> FIXTURE_PROJECT_NAME_PREFIXES = List.of("项目I4");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // R37 fixture-scoped teardown : every seeded row hangs off
        // the I4 project-name family (exec tasks/batches through the project-keyed
        // default sweep, scripts and versions through the FK walk, user_project and
        // project_env through their FKs, data pool items through the explicit
        // extras). tp_batch_case carries no FK and no project column: the join
        // purge runs first, while batch rows are still present. Executors drop by
        // their ex-i4% name family, and the EXECUTOR_* notifications the offline
        // detection raised for them drop through the dedup key BEFORE the executor
        // rows. The old blanket TRUNCATE wiped shipped projects/users and the
        // live dev-slot runner's executor row on the shared dev database.
        List<Long> family = SharedDbFixtureSweep.projectIdsByNamePrefixes(
                jdbcTemplate, FIXTURE_PROJECT_NAME_PREFIXES);
        SharedDbFixtureSweep.batchCaseRowsOfProjects(jdbcTemplate, family);
        for (Long ex : jdbcTemplate.queryForList(
                "SELECT id FROM tp_executor WHERE name LIKE 'ex-i4%'", Long.class)) {
            jdbcTemplate.update("DELETE FROM tp_notification WHERE dedup_key IN (?,?) "
                            + "OR dedup_key LIKE ? OR dedup_key LIKE ?",
                    "EXECUTOR_OFFLINE:" + ex, "EXECUTOR_ONLINE:" + ex,
                    "EXECUTOR_OFFLINE:" + ex + ":%", "EXECUTOR_ONLINE:" + ex + ":%");
        }
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-i4%'");
        SharedDbFixtureSweep.byProjectList(jdbcTemplate,
                List.of("tp_data_pool_item"), family);
        // Anti-pollution: drop leftover config rows AND evict the in-process cache entry.
        // ConfigCacheHolder is loaded once at startup and only synced by the ConfigService
        // write path, so a raw DELETE below bypasses it and leaves the shipped seed value
        // (exec.case-timeout-minutes='60' seeded by the timeout-keys migration), which
        // would make the "missing key falls back to 10 minutes" assertion read 60.
        // After remove(), get() returns null and TimeoutConfigSupport truly takes the
        // default branch.
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", CASE_TIMEOUT_KEY);
        configCacheHolder.remove(CASE_TIMEOUT_KEY);
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    private long createEnv(long pid) {
        return projectEnvService.create(pid, new ProjectEnvInput("测试环境", 2,
                "https://t.example.com", null, "web", "chromium", "v1.0.0"));
    }

    private long createCase(long pid, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of()));
        return info.id();
    }

    
    private void approve(long... caseIds) {
        for (long id : caseIds) {
            jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", id);
        }
    }

    private void withScript(long caseId) {
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                "{\"caseId\":" + caseId + ",\"steps\":[]}", 2));
    }

    /**
     * H4 pool isolation (same shape as BatchCaseRerunTest, the group-17 pioneer):
     * the batch pins a project-owned resource-pool tag (tp_tag_dict type=7) and
     * only pool-member fixture executors can claim its tasks - the live dev-slot
     * runner skips the candidate at queue level, so pending fixture tasks are
     * never stolen (the retired blanket TRUNCATE of tp_executor used to sideline
     * it only by accident).
     */
    private long createPool(long pid) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_tag_dict (project_id, name, type) VALUES (?, '超时资源池', 7) RETURNING id
                """, Long.class, pid);
    }

    private long registerExecutor(String name, long poolTagId) {
        return executorService.register(new ExecutorRegisterInput(
                name, String.valueOf(poolTagId), null, "web", "chromium", 2, "1.0.0", 0));
    }

    /** Start a batch over N approved+scripted cases: every task pending (1), claimable only inside the pinned pool. */
    private BatchInfo startedBatch(long pid, long env, int caseCount, long poolTagId) {
        List<Long> caseIds = new java.util.ArrayList<>();
        for (int i = 1; i <= caseCount; i++) {
            long c = createCase(pid, "超时用例" + i);
            approve(c);
            withScript(c);
            caseIds.add(c);
        }
        BatchInfo batch = batchService.create(1L,
                new BatchCreateInput(pid, "超时批", env, caseIds, null, null, poolTagId));
        batchService.start(1L, batch.id());
        return batch;
    }

    
    private void ageTask(long taskId, int minutes) {
        jdbcTemplate.update("UPDATE tp_exec_task SET started_at = now() - make_interval(mins => ?) WHERE id = ?",
                minutes, taskId);
    }

    private int taskStatus(long taskId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        return status == null ? -1 : status;
    }


    @Test
    void test_i4_running_task_timeout_marks_5_and_finalizes_batch_partial() {
        long pid = createProject("I4A");
        long env = createEnv(pid);
        long pool = createPool(pid);
        BatchInfo batch = startedBatch(pid, env, 2, pool);
        long exId = registerExecutor("ex-i4a", pool);
        ClaimTaskView first = execTaskService.claim(exId);
        assertNotNull(first, "首任务已领取=执行中(2)");
        long firstTaskId = first.taskId();
        long pendingTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE batch_id = ? AND status = 1 ORDER BY id LIMIT 1",
                Long.class, batch.id());
        long batchCaseId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_batch_case WHERE batch_id = ? ORDER BY id LIMIT 1", Long.class, batch.id());
        jdbcTemplate.update("INSERT INTO tp_data_pool_item (project_id, env_id, type, value, value_hash,"
                + " status, locked_by, locked_at) VALUES (?, ?, '账号', 'acc-1', 'h-i4a', 2, ?, now())",
                pid, env, batchCaseId);

        ageTask(firstTaskId, 20);
        int swept = execTaskService.sweepTimeoutTasks();

        assertEquals(1, swept, "仅过期在跑任务被扫中");
        assertEquals(5, taskStatus(firstTaskId), "超时任务=5（10 §2 has_fail 判定源）");
        String detail = jdbcTemplate.queryForObject(
                "SELECT error_detail FROM tp_exec_task WHERE id = ?", String.class, firstTaskId);
        assertNotNull(detail);
        assertTrue(detail.contains("超时"), "人话失败原因点名超时：" + detail);
        Integer runningCnt = jdbcTemplate.queryForObject(
                "SELECT running_cnt FROM tp_executor WHERE id = ?", Integer.class, exId);
        assertEquals(0, runningCnt, "执行器容量即时释放（断点坑③）");
        assertTrue(lockedPoolItems(batchCaseId).isEmpty(),
                "N2 池占用即时释放，不等 30 分钟兜底扫描（TC-I4-01 释放环境锁）");
        assertEquals(4, batchService.get(batch.id()).status(), "有待派发不提前收口（批次续跑不挂）");

        ClaimTaskView second = execTaskService.claim(exId);
        assertNotNull(second, "容量释放后第二任务可领取（批次续跑）");
        assertEquals(pendingTaskId, second.taskId());
        ageTask(pendingTaskId, 20);
        assertEquals(1, execTaskService.sweepTimeoutTasks(), "末条超时同样扫中");
        assertEquals(5, taskStatus(pendingTaskId));
        assertEquals(7, batchService.get(batch.id()).status(), "全终态含超时 → 部分失败(7)");
    }

    private List<Integer> lockedPoolItems(long batchCaseId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM tp_data_pool_item WHERE locked_by = ? AND status = 2",
                Integer.class, batchCaseId);
    }


    @Test
    void test_i4_fresh_task_not_swept_and_threshold_config_hot_update() {
        long pid = createProject("I4B");
        long env = createEnv(pid);
        long pool = createPool(pid);
        startedBatch(pid, env, 1, pool);
        long exId = registerExecutor("ex-i4b", pool);
        ClaimTaskView claimed = execTaskService.claim(exId);
        assertNotNull(claimed);

        assertEquals(0, execTaskService.sweepTimeoutTasks(), "刚领取的在跑任务不被误伤");
        assertEquals(2, taskStatus(claimed.taskId()));

        ConfigInfo cfg = configService.create(1L, new ConfigInput(
                CASE_TIMEOUT_KEY, "1", "系统", "int", 0, "单条超时分钟（I4）"));
        try {
            ageTask(claimed.taskId(), 2);
            assertEquals(1, execTaskService.sweepTimeoutTasks(), "阈值 1 分钟即时生效");
            assertEquals(5, taskStatus(claimed.taskId()));
        } finally {
            configService.delete(1L, cfg.id());
        }
    }


    @Test
    void test_i4_dryrun_task_timeout_no_batch_side_effects() {
        long pid = createProject("I4C");
        long env = createEnv(pid);
        long pool = createPool(pid);
        long caseId = createCase(pid, "试运行用例");
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, env));
        long exId = registerExecutor("ex-i4c", pool);
        execTaskService.claim(exId);
        ageTask(taskId, 20);

        assertEquals(1, execTaskService.sweepTimeoutTasks(), "试运行任务同样纳入超时扫描（不悬空）");
        assertEquals(5, taskStatus(taskId));
        Integer runningCnt = jdbcTemplate.queryForObject(
                "SELECT running_cnt FROM tp_executor WHERE id = ?", Integer.class, exId);
        assertEquals(0, runningCnt, "容量释放");
        // Scoped to this fixture project: a global zero-row count only held in the
        // retired TRUNCATE world; on the shared dev database other suites' batches
        // legitimately coexist.
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_case WHERE batch_id IN"
                        + " (SELECT id FROM tp_exec_batch WHERE project_id = ?)",
                Long.class, pid), "试运行任务不产生批次明细");
    }


    @Test
    void test_i4_offline_executor_task_redistributed_with_token_rotation() {
        long pid = createProject("I4D");
        long env = createEnv(pid);
        long pool = createPool(pid);
        BatchInfo batch = startedBatch(pid, env, 1, pool);
        long deadId = registerExecutor("ex-i4d-dead", pool);
        ClaimTaskView claimed = execTaskService.claim(deadId);
        assertNotNull(claimed);
        String staleToken = claimed.claimToken();

        jdbcTemplate.update("UPDATE tp_executor SET last_heartbeat = now() - interval '40 seconds' WHERE id = ?",
                deadId);
        assertEquals(1, executorService.detectOfflineAndNotify(), "失联执行器被 N3 判离线");

        int moved = execTaskService.redistributeTasksOfOfflineExecutors();
        assertEquals(1, moved, "离线执行器名下在跑任务重派（不悬空）");
        assertEquals(1, taskStatus(claimed.taskId()), "任务回待派发(1)");
        String newToken = jdbcTemplate.queryForObject(
                "SELECT claim_token FROM tp_exec_task WHERE id = ?", String.class, claimed.taskId());
        assertNotEquals(staleToken, newToken, "claim_token 已轮换（断点坑③防僵尸回传）");
        Integer deadRunning = jdbcTemplate.queryForObject(
                "SELECT running_cnt FROM tp_executor WHERE id = ?", Integer.class, deadId);
        assertEquals(0, deadRunning, "原执行器容量释放");

        long liveId = registerExecutor("ex-i4d-live", pool);
        ClaimTaskView reclaimed = execTaskService.claim(liveId);
        assertNotNull(reclaimed, "重派后新执行器可领");
        assertEquals(claimed.taskId(), reclaimed.taskId());
        ServiceException zombie = assertThrows(ServiceException.class, () -> execTaskService.submitResult(
                new ExecResultInput(claimed.taskId(), staleToken, "pass",
                        null, null, null, null, 100, null)));
        assertEquals(BizErrorCode.VERSION_CONFLICT, zombie.getCode(), "旧令牌=40901 拒绝僵尸回传");
        execTaskService.submitResult(new ExecResultInput(
                claimed.taskId(), reclaimed.claimToken(), "pass",
                null, null, null, null, 100, null));
        assertEquals(3, taskStatus(claimed.taskId()));
        assertEquals(6, batchService.get(batch.id()).status(), "重派链末端批次正常收口 6");
    }


    @Test
    void test_i4_claim_response_carries_timeout_ms_from_config() {
        long pid = createProject("I4E");
        long env = createEnv(pid);
        long pool = createPool(pid);
        startedBatch(pid, env, 2, pool);
        long exId = registerExecutor("ex-i4e", pool);

        ClaimTaskView claimed = execTaskService.claim(exId);
        assertNotNull(claimed);
        assertEquals(600_000L, claimed.timeoutMs(), "缺键下发默认 600000ms");

        ConfigInfo cfg = configService.create(1L, new ConfigInput(
                CASE_TIMEOUT_KEY, "1", "系统", "int", 0, "单条超时分钟（I4）"));
        try {
            ClaimTaskView second = execTaskService.claim(exId);
            assertNotNull(second, "容量 2 内第二任务可领");
            assertEquals(60_000L, second.timeoutMs(), "阈值热更即时联动 claim 下发值");
        } finally {
            configService.delete(1L, cfg.id());
        }
    }
}
