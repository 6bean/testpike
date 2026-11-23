package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false",
        "testpike.prod-blacklist=*.prod-demo.com"})
class BatchWatchdogTest {

    private static final String BATCH_TIMEOUT_KEY = "exec.batch-timeout-hours";

    @Autowired
    private BatchService batchService;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
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

    // Fixture-scoped teardown (truncate-ratchet discipline, sub-batch H):
    // projects live in the name family below (codes WD*); the FK child-first
    // sweep covers tp_script/tp_test_case/tp_project_env/tp_user_project and
    // the project-keyed defaults tp_exec_task/tp_exec_batch. tp_batch_case
    // has no FK and no project column, so this family's rows purge first;
    // the watchdog cancels overdue batches (8), which emits BATCH_FINISHED
    // in-site rows with no project FK, so they purge through the dedup-key
    // batch segment. Executors purge by the 'ex-wd' name family. The config
    // key delete below is pre-existing suite design (two tests pin the
    // key-absent default of 4h; the factory seed drift it leaves is
    // re-seeded by the baseline gate at tick close). The old CASCADE wiped
    // shipped tp_user rows and every other suite's data; the sweep leaves
    // them alone.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "项目WD";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        java.util.List<Long> familyIds =
                com.sixbean.testpike.core.support.SharedDbFixtureSweep.projectIdsByNamePrefixes(
                        jdbcTemplate, List.of(FIXTURE_PROJECT_NAME_PREFIX));
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchCaseRowsOfProjects(
                jdbcTemplate, familyIds);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchNotificationsOfProjects(
                jdbcTemplate, familyIds);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of(), List.of(FIXTURE_PROJECT_NAME_PREFIX));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-wd%'");
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", BATCH_TIMEOUT_KEY);
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

    
    private BatchInfo startedBatch(long pid, long env, int caseCount, String name) {
        List<Long> caseIds = new java.util.ArrayList<>();
        for (int i = 1; i <= caseCount; i++) {
            long c = createCase(pid, name + "用例" + i);
            approve(c);
            withScript(c);
            caseIds.add(c);
        }
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, name, env, caseIds, null));
        batchService.start(1L, batch.id());
        return batch;
    }

    private void ageBatch(long batchId, int hours) {
        jdbcTemplate.update("UPDATE tp_exec_batch SET started_at = now() - make_interval(hours => ?) WHERE id = ?",
                hours, batchId);
    }

    private int taskStatus(long taskId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        return status == null ? -1 : status;
    }


    @Test
    void test_i4_watchdog_batch_total_timeout_cancels_with_pending_marked() {
        long pid = createProject("WD1");
        long env = createEnv(pid);
        BatchInfo batch = startedBatch(pid, env, 2, "看门狗批");
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-wd1", null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView running = execTaskService.claim(exId);
        assertNotNull(running, "首任务领取→批次执行中(4)");
        long pendingTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE batch_id = ? AND status = 1 ORDER BY id LIMIT 1",
                Long.class, batch.id());
        assertEquals(4, batchService.get(batch.id()).status());

        ageBatch(batch.id(), 5);
        int swept = batchService.sweepWatchdogTimeoutBatches();

        assertEquals(1, swept, "超期执行中批被看门狗收口");
        BatchInfo after = batchService.get(batch.id());
        assertEquals(8, after.status(), "watchdog_timeout → 已取消(8) 终态（10 §2/S8）");
        assertNotNull(after.finishedAt(), "finished_at 落位");
        assertEquals(6, taskStatus(pendingTaskId), "待派发任务出队打标(6)");
        assertEquals(2, taskStatus(running.taskId()), "在跑任务不强杀（语义同 pause）");
    }


    @Test
    void test_i4_watchdog_fresh_batch_not_touched_and_threshold_hot_update() {
        long pid = createProject("WD2");
        long env = createEnv(pid);
        BatchInfo batch = startedBatch(pid, env, 1, "新批");
        assertEquals(2, batchService.get(batch.id()).status(), "未领取=待执行(2)仍在域内");

        assertEquals(0, batchService.sweepWatchdogTimeoutBatches(), "刚启动批不误伤");
        assertEquals(2, batchService.get(batch.id()).status());

        ConfigInfo cfg = configService.create(1L, new ConfigInput(
                BATCH_TIMEOUT_KEY, "1", "系统", "int", 0, "批次总超时小时（I4）"));
        try {
            ageBatch(batch.id(), 2);
            assertEquals(1, batchService.sweepWatchdogTimeoutBatches(), "阈值 1 小时即时生效");
            BatchInfo after = batchService.get(batch.id());
            assertEquals(8, after.status());
            assertTrue(after.finishedAt() != null, "收口批次 finished_at 落位");
        } finally {
            configService.delete(1L, cfg.id());
        }
    }


    @Test
    void test_i4_watchdog_skips_draft_batches() {
        long pid = createProject("WD3");
        long env = createEnv(pid);
        long c = createCase(pid, "草稿用例");
        approve(c);
        withScript(c);
        BatchInfo draft = batchService.create(1L, new BatchCreateInput(pid, "草稿批", env, List.of(c), null));
        jdbcTemplate.update("UPDATE tp_exec_batch SET started_at = now() - interval '30 days' WHERE id = ?",
                draft.id());
        assertEquals(0, batchService.sweepWatchdogTimeoutBatches(), "草稿(1)不在看门狗域（仅 2/4/5 运行域）");
        assertEquals(1, batchService.get(draft.id()).status(), "草稿态零触碰");
    }
}
