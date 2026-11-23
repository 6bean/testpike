package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchRecoveryView;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
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
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {
        "testpike.scheduling.enabled=false",
        "testpike.prod-blacklist=*.prod-demo.com"})
class T256BatchRecoveryTest {

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
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (truncate-ratchet discipline, sub-batch H):
    // projects live in the name family below (codes RC*); the FK child-first
    // sweep covers tp_script/tp_test_case/tp_project_env/tp_user_project and
    // the project-keyed defaults tp_exec_task/tp_exec_batch. tp_batch_case
    // has no FK and no project column, so this family's rows purge first;
    // recover finalizes batches (6/7), which emits BATCH_FAILED/BATCH_FINISHED
    // in-site rows with no project FK, so they purge through the dedup-key
    // batch segment while the batch rows still exist. Executors purge by the
    // 'ex-rc' name family. The old CASCADE wiped shipped tp_user rows and
    // every other suite's data — the sweep leaves them alone.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "项目RC";

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
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-rc%'");
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
        List<Long> caseIds = new ArrayList<>();
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

    private long registerExecutor(String name, int capacity) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", capacity, "1.0.0", 0));
    }

    private void markExecutorOffline(long executorId) {
        jdbcTemplate.update("UPDATE tp_executor SET status = 3 WHERE id = ?", executorId);
    }

    private int taskStatus(long taskId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        return status == null ? -1 : status;
    }

    private long countTasks(long batchId, String statusCond) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND " + statusCond,
                Long.class, batchId);
        return n == null ? 0 : n;
    }


    @Test
    void test_recover_finalizes_batch_stuck_running_with_all_tasks_terminal() {
        long pid = createProject("RC1");
        long env = createEnv(pid);
        BatchInfo batch = startedBatch(pid, env, 2, "崩溃收口批");
        long exId = registerExecutor("ex-rc1", 2);
        assertNotNull(execTaskService.claim(exId), "首领取把批次推到执行中(4)");
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 3, finished_at = now() "
                + "WHERE batch_id = ? AND id = (SELECT min(id) FROM tp_exec_task WHERE batch_id = ?)",
                batch.id(), batch.id());
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 4, finished_at = now() "
                + "WHERE batch_id = ? AND status NOT IN (3, 4)", batch.id());
        batchService.pause(1L, batch.id());
        assertEquals(5, batchService.get(batch.id()).status());

        BatchRecoveryView view = batchService.recover(1L, batch.id());

        BatchInfo after = batchService.get(batch.id());
        assertEquals(7, after.status(), "存在失败任务→部分失败(7) 补收口（10 §2 has_fail）");
        assertNotNull(after.finishedAt(), "finished_at 落位");
        assertEquals(7, view.batchStatus(), "对账视图回显新状态");
        assertEquals(2, view.terminalCount(), "对账终态计数=total");
        assertEquals(0, view.runningCount());
        assertEquals(0, view.pendingCount());
        assertNotNull(view.message(), "人话对账 message");
    }


    @Test
    void test_recover_reclaims_offline_running_task_and_reclaimable() {
        long pid = createProject("RC2");
        long env = createEnv(pid);
        BatchInfo batch = startedBatch(pid, env, 2, "崩溃回收批");
        long exId = registerExecutor("ex-rc2", 1);
        ClaimTaskView claimed = execTaskService.claim(exId);
        assertNotNull(claimed);
        long runningTaskId = claimed.taskId();
        markExecutorOffline(exId);

        BatchRecoveryView view = batchService.recover(1L, batch.id());

        assertEquals(1, view.recoveredCount(), "离线执行器名下在跑任务被回收 1 条");
        assertEquals(1, taskStatus(runningTaskId), "回收后回待派发(1)");
        assertEquals(4, batchService.get(batch.id()).status(), "仍有未终态任务→批次保持执行中(4)");
        assertEquals(2, view.pendingCount(), "对账待派发=2（原排队 1 + 回收回来 1）");
        assertEquals(0, view.runningCount(), "回收后在跑清零");
        assertEquals(0, view.terminalCount(), "本场景无终态任务");
        long ex2 = registerExecutor("ex-rc2b", 1);
        ClaimTaskView reclaimed = execTaskService.claim(ex2);
        assertNotNull(reclaimed, "回收任务可被新执行器再领");
        assertEquals(runningTaskId, reclaimed.taskId(), "再领到的正是回收任务");
    }


    @Test
    void test_recover_leaves_running_tasks_of_online_executor_untouched() {
        long pid = createProject("RC3");
        long env = createEnv(pid);
        BatchInfo batch = startedBatch(pid, env, 2, "在线不误杀批");
        long exId = registerExecutor("ex-rc3", 1);
        ClaimTaskView claimed = execTaskService.claim(exId);
        assertNotNull(claimed);

        BatchRecoveryView view = batchService.recover(1L, batch.id());

        assertEquals(0, view.recoveredCount(), "执行器在线=零回收");
        assertEquals(2, taskStatus(claimed.taskId()), "在跑任务态零触碰（禁强杀）");
        assertEquals(1, view.runningCount(), "对账在跑=1 不受影响");
        assertTrue(view.message().contains("等待") || view.message().contains("回传"),
                "人话提示等待回传：" + view.message());
        execTaskService.submitResult(new com.sixbean.testpike.core.executor.api.dto.ExecResultInput(
                claimed.taskId(), claimed.claimToken(), "pass", null, null,
                null, null, 1000, null));
        assertEquals(3, taskStatus(claimed.taskId()), "在线任务正常回传终态");
        assertEquals(4, batchService.get(batch.id()).status(), "仍有未终态任务不提前收口");
    }


    @Test
    void test_recover_rejects_out_of_domain_status_with_plain_message() {
        long pid = createProject("RC4");
        long env = createEnv(pid);
        BatchInfo queued = startedBatch(pid, env, 1, "待执行批");
        assertThrows(ServiceException.class, () -> batchService.recover(1L, queued.id()),
                "待执行(2) 无崩溃语义不入恢复域");

        for (int st : new int[]{6, 7, 8}) {
            long id = jdbcTemplate.queryForObject(
                    "INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type, schedule_type)"
                            + " VALUES (?, ?, 'B-RC4-' || ?, '域外批', ?, 1, 0) RETURNING id",
                    Long.class, pid, env, st, st);
            ServiceException e = assertThrows(ServiceException.class,
                    () -> batchService.recover(1L, id));
            assertEquals(BizErrorCode.STATE_MACHINE_REJECT, e.getCode());
            assertTrue(e.getMessage().contains(BatchInfo.statusLabel(st)),
                    "人话点名当前状态：" + e.getMessage());
        }
    }

    // ── ⑥ sweep-vs-manual reclaim reconciliation: when the 15s auto sweep wins the
    // race and reclaims this batch's dangling tasks first, the manual recover view and
    // its plain-language message must state how many tasks were already reclaimed by
    // the automatic sweep (no silent "nothing to reclaim" that the user cannot audit) ──

    @Test
    void test_recover_reports_tasks_already_reclaimed_by_auto_sweep() {
        long pid = createProject("RC6");
        long env = createEnv(pid);
        BatchInfo batch = startedBatch(pid, env, 3, "巡检抢收批");
        long exId = registerExecutor("ex-rc6", 2);
        assertNotNull(execTaskService.claim(exId), "首领取把批次推到执行中(4)");
        assertNotNull(execTaskService.claim(exId), "同执行器再领一条在跑任务");
        markExecutorOffline(exId);

        // Simulate the sweep job winning the race: both dangling tasks go back to the
        // pending queue before the operator clicks manual recovery.
        int swept = execTaskService.redistributeTasksOfOfflineExecutors();
        assertEquals(2, swept, "自动巡检路径回收 2 条失联任务");

        BatchRecoveryView view = batchService.recover(1L, batch.id());

        assertEquals(0, view.recoveredCount(), "本次手动回收为零（候选已被巡检清空）");
        assertEquals(2, view.sweepReclaimedCount(),
                "对账视图须如实回显已被自动巡检回收且仍排队中的任务数");
        assertTrue(view.message().contains("自动巡检"),
                "人话对账声明须点名自动巡检回收去向：" + view.message());
    }


    @Test
    void test_recover_idempotent_second_call_reclaims_zero() {
        long pid = createProject("RC5");
        long env = createEnv(pid);
        BatchInfo batch = startedBatch(pid, env, 2, "幂等批");
        long exId = registerExecutor("ex-rc5", 1);
        ClaimTaskView claimed = execTaskService.claim(exId);
        assertNotNull(claimed);
        markExecutorOffline(exId);

        BatchRecoveryView first = batchService.recover(1L, batch.id());
        assertEquals(1, first.recoveredCount());
        BatchRecoveryView second = batchService.recover(1L, batch.id());

        assertEquals(0, second.recoveredCount(), "第二次回收 0 条（条件更新天然幂等）");
        assertEquals(first.pendingCount(), second.pendingCount(), "对账计数零变更");
        assertEquals(first.batchStatus(), second.batchStatus(), "批次状态零变更");
        assertEquals(1, taskStatus(claimed.taskId()), "任务保持待派发(1)");
    }
}
