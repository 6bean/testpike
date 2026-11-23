package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchUpdateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class BatchVersionOptimisticLockTest {

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
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (truncate-ratchet discipline, sub-batch H):
    // projects live in the name families below (codes LV*/SV*/PV*/RV*/CV*/
    // UV*); the FK child-first sweep covers tp_script/tp_test_case/
    // tp_project_env/tp_user_project under tp_project and tp_exec_task/
    // tp_exec_batch among the project-keyed defaults. tp_batch_case carries
    // no FK and no project column, so this family's rows purge first;
    // tp_notification carries no project FK at all, so the batch in-site
    // rows (BATCH_FAILED/BATCH_FINISHED) purge through the dedup-key batch
    // segment while the batch rows still exist. This suite's executors purge
    // by their ex-pv/ex-rv/ex-cv name families. The old CASCADE wiped
    // shipped tp_user rows and every other suite's executors — the sweep
    // leaves them alone.
    private static final List<String> FIXTURE_PROJECT_NAME_PREFIXES =
            List.of("项目LV", "项目SV", "项目PV", "项目RV", "项目CV", "项目UV");

    @BeforeEach
    @AfterEach
    void clean() {
        List<Long> familyIds =
                com.sixbean.testpike.core.support.SharedDbFixtureSweep.projectIdsByNamePrefixes(
                        jdbcTemplate, FIXTURE_PROJECT_NAME_PREFIXES);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchCaseRowsOfProjects(
                jdbcTemplate, familyIds);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchNotificationsOfProjects(
                jdbcTemplate, familyIds);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of(), FIXTURE_PROJECT_NAME_PREFIXES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-pv%'"
                + " OR name LIKE 'ex-rv%' OR name LIKE 'ex-cv%'");
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    private long createEnv(long pid, String name) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, 2,
                "https://" + name + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    private long createApprovedCase(long pid, String title) {
        long id = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of())).id();
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", id);
        scriptService.save(new ScriptSaveInput(id, "v2",
                "{\"caseId\":" + id + ",\"steps\":[]}", 2));
        return id;
    }

    private BatchInfo draftBatch(long pid, long env, String name, int caseCount) {
        List<Long> caseIds = new java.util.ArrayList<>();
        for (int i = 1; i <= caseCount; i++) {
            caseIds.add(createApprovedCase(pid, name + "用例" + i));
        }
        return batchService.create(1L, new BatchCreateInput(pid, name, env, caseIds, null));
    }

    private BatchInfo startedAndClaimed(long pid, long env, String name, int caseCount, String exName) {
        BatchInfo batch = draftBatch(pid, env, name, caseCount);
        batchService.start(1L, batch.id());
        long exId = executorService.register(new ExecutorRegisterInput(
                exName, null, null, "web", "chromium", 2, "1.0.0", 0));
        assertNotNull(execTaskService.claim(exId), "首任务可领取");
        return batchService.get(batch.id());
    }


    @Test
    void test_it_lock_04_existing_rows_version_default_zero() {
        long pid = createProject("LV04");
        long env = createEnv(pid, "测试环境");
        BatchInfo batch = draftBatch(pid, env, "存量批", 1);
        long notifId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_notification (user_id, event_type, title, content) VALUES (1, 'test', 't', 'c') RETURNING id",
                Long.class);
        try {
            Integer batchVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM tp_exec_batch WHERE id = ?", Integer.class, batch.id());
            Integer notifVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM tp_notification WHERE id = ?", Integer.class, notifId);
            Integer envVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM tp_project_env WHERE id = ?", Integer.class, env);
            assertNotNull(batchVersion, "tp_exec_batch.version 非 NULL");
            assertNotNull(notifVersion, "tp_notification.version 非 NULL");
            assertNotNull(envVersion, "tp_project_env.version 非 NULL");
            assertEquals(0, batchVersion, "存量批 version=0（V31 DEFAULT 0）");
            assertEquals(0, notifVersion, "存量通知 version=0");
            assertEquals(0, envVersion, "存量环境 version=0");
        } finally {
            // tp_notification carries no project FK, so the family sweep
            // cannot reach this probe row: delete it by its own id.
            jdbcTemplate.update("DELETE FROM tp_notification WHERE id = ?", notifId);
        }
    }


    @Test
    void test_start_bumps_batch_version() {
        long pid = createProject("SV01");
        long env = createEnv(pid, "测试环境");
        BatchInfo draft = draftBatch(pid, env, "启动批", 1);
        assertEquals(0, draft.version(), "草稿创建 version=0");
        batchService.start(1L, draft.id());
        BatchInfo after = batchService.get(draft.id());
        assertEquals(1, after.version(), "start 后 version=1（状态变更 version+1）");
    }


    @Test
    void test_pause_stale_version_throws_40901() {
        long pid = createProject("PV01");
        long env = createEnv(pid, "测试环境");
        BatchInfo batch = draftBatch(pid, env, "暂停批", 2);
        batchService.start(1L, batch.id());
        int staleVersion = batchService.get(batch.id()).version();
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-pv01", null, null, "web", "chromium", 2, "1.0.0", 0));
        assertNotNull(execTaskService.claim(exId), "甲侧 claim 推进 2→4 并 bump version");
        OptimisticLockingException ex = assertThrows(OptimisticLockingException.class,
                () -> batchService.pause(1L, batch.id(), staleVersion));
        assertEquals(BizErrorCode.VERSION_CONFLICT, OptimisticLockingException.CODE, "业务码=40901");
        assertTrue(ex.getMessage().contains("刷新"), "人话含「刷新」：" + ex.getMessage());
        BatchInfo after = batchService.get(batch.id());
        assertEquals(staleVersion + 1, after.version(), "DB version 只涨一次（陈旧提交零副作用）");
        assertEquals(4, after.status(), "陈旧 pause 不得改变状态");
    }


    @Test
    void test_pause_correct_version_bumps_version() {
        long pid = createProject("PV02");
        long env = createEnv(pid, "测试环境");
        BatchInfo batch = startedAndClaimed(pid, env, "暂停批2", 2, "ex-pv02");
        int v0 = batch.version();
        BatchInfo paused = batchService.pause(1L, batch.id(), v0);
        assertEquals(5, paused.status(), "暂停态=5");
        assertEquals(v0 + 1, paused.version(), "pause 后 version+1");
    }


    @Test
    void test_resume_stale_version_throws_40901() {
        long pid = createProject("RV01");
        long env = createEnv(pid, "测试环境");
        BatchInfo batch = startedAndClaimed(pid, env, "续跑批", 2, "ex-rv01");
        batchService.pause(1L, batch.id(), batch.version());
        BatchInfo paused = batchService.get(batch.id());
        int staleVersion = paused.version() - 1;
        assertThrows(OptimisticLockingException.class,
                () -> batchService.resume(1L, batch.id(), staleVersion));
        assertEquals(BizErrorCode.VERSION_CONFLICT, OptimisticLockingException.CODE);
    }


    @Test
    void test_cancel_stale_version_throws_40901() {
        long pid = createProject("CV01");
        long env = createEnv(pid, "测试环境");
        BatchInfo batch = startedAndClaimed(pid, env, "取消批", 2, "ex-cv01");
        int staleVersion = batch.version() - 1;
        assertThrows(OptimisticLockingException.class,
                () -> batchService.cancel(1L, batch.id(), staleVersion));
    }


    @Test
    void test_draft_update_bumps_version() {
        long pid = createProject("UV01");
        long env = createEnv(pid, "测试环境");
        BatchInfo draft = draftBatch(pid, env, "编辑批", 1);
        assertEquals(0, draft.version());
        BatchInfo updated = batchService.update(1L, draft.id(),
                new BatchUpdateInput("改名校验", null, null, null), draft.version());
        assertEquals(1, updated.version(), "编辑后 version=1");
        assertEquals("改名校验", updated.name());
    }


    @Test
    void test_draft_update_stale_version_throws_40901() {
        long pid = createProject("UV02");
        long env = createEnv(pid, "测试环境");
        BatchInfo draft = draftBatch(pid, env, "编辑批2", 1);
        batchService.update(1L, draft.id(),
                new BatchUpdateInput("甲改", null, null, null), draft.version());
        assertThrows(OptimisticLockingException.class,
                () -> batchService.update(1L, draft.id(),
                        new BatchUpdateInput("乙改", null, null, null), draft.version()));
    }
}
