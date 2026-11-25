package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.executor.api.dto.BatchCancelResult;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.api.dto.RunningTaskView;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Real dev database, nothing mocked (strict-testing-standards); teardown
 * sweeps only this suite's fixture project family (no blanket truncate).
 */
@SpringBootTest(classes = CoreApplication.class)
class ExecTaskCancelTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local/login\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectEnvService projectEnvService;

    // Fixture-scoped teardown : name-family sweep walks the FK
    // graph from this suite's fixed-name project; tp_exec_task is covered by
    // the helper's default project-keyed set. Executors drop on the
    // 'ex-cancel-' prefix, which is unique to this suite (repo-wide grep,
    // 2026-11-15) so no other fixture's rows are touched.
    private static final List<String> FIXTURE_PROJECT_NAMES = List.of("取消项目");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // tp_data_pool_item is not covered by the name-family sweep: rows pinned to
        // this suite's fixture projects are removed explicitly, both before and
        // after each case, so an aborted run can never leak a row into the next
        // run's unique (env_id, type, value_hash) insert
        jdbcTemplate.update("DELETE FROM tp_data_pool_item WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name = '取消项目')");
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of(), FIXTURE_PROJECT_NAMES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-cancel-%'");
    }

    private long registerExecutor(String name, int capacity) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", capacity, "1.0.0", 0));
    }

    private long createProject() {
        return projectService.create(1L, new ProjectCreateInput("取消项目",
                "CX" + System.nanoTime() % 100000, "zh-CN", "P1", null));
    }

    private long createApprovedCase(long projectId) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, "取消链路用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    private long createEnv(long projectId) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1"));
    }

    
    private ExecTaskInfo runningTask(long executorId) {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createApprovedCase(pid);
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));
        ClaimTaskView view = execTaskService.claim(executorId);
        assertNotNull(view, "执行器必须领取到任务");
        assertEquals(taskId, view.taskId());
        return execTaskService.get(taskId);
    }


    @Test
    void cancel_执行中任务_置已取消终态并写人话原因() {
        long exId = registerExecutor("ex-cancel-1", 2);
        ExecTaskInfo task = runningTask(exId);

        execTaskService.cancel(1L, task.id());

        ExecTaskInfo after = execTaskService.get(task.id());
        assertEquals(7, after.status(), "V11：终止后终态=7 已取消");
        assertNotNull(after.finishedAt(), "终止必须落 finished_at（时长面收口）");
        assertNotNull(after.errorDetail());
        assertTrue(after.errorDetail().contains("取消"), "error_detail 必须人话说明取消来源：" + after.errorDetail());
    }

    @Test
    void cancel_释放执行器容量与数据池占用() {
        long exId = registerExecutor("ex-cancel-2", 1);
        ExecTaskInfo task = runningTask(exId);
        // the pool row must sit on the fixture project's own env: the previous probe
        // picked the globally smallest env id, which belongs to another project and
        // let a leaked earlier row collide on uq_pool_env_type_hash
        Long envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? ORDER BY id LIMIT 1",
                Long.class, task.projectId());
        jdbcTemplate.update("INSERT INTO tp_data_pool_item (project_id, env_id, type, value, " +
                "value_hash, status, locked_by) VALUES (?, ?, 'account', 'u1', 'h1', 2, ?)",
                task.projectId(), envId, task.id());

        execTaskService.cancel(1L, task.id());

        Integer busy = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_pool_item WHERE status = 2 AND locked_by = ?",
                Integer.class, task.id());
        assertEquals(0, busy, "终止必须同 sweep 语义即时释放池项占用");
        Integer running = jdbcTemplate.queryForObject(
                "SELECT running_cnt FROM tp_executor WHERE id = ?", Integer.class, exId);
        assertEquals(0, running, "终止必须释放执行器在跑容量（红线6 复用 sweep 释放族）");
    }

    @Test
    void cancel_已终态任务_40902人话拒绝() {
        long exId = registerExecutor("ex-cancel-3", 2);
        ExecTaskInfo task = runningTask(exId);
        execTaskService.cancel(1L, task.id());

        ServiceException err = assertThrows(ServiceException.class,
                () -> execTaskService.cancel(1L, task.id()));
        assertEquals(40902, err.getCode(), "已终态重复终止=状态机拒绝（IT-V11-01 幂等 409 人话）");
        assertTrue(err.getMessage().contains("取消") || err.getMessage().contains("终态"),
                "人话指引：" + err.getMessage());
    }

    @Test
    void cancel_与超时扫描竞态_行级条件更新单胜出() {
        long exId = registerExecutor("ex-cancel-4", 2);
        ExecTaskInfo task = runningTask(exId);
        jdbcTemplate.update("UPDATE tp_exec_task SET started_at = now() - interval '999 minutes' WHERE id = ?",
                task.id());

        execTaskService.cancel(1L, task.id());
        int swept = execTaskService.sweepTimeoutTasks();

        assertEquals(0, swept, "已被取消(7)的任务不得被超时扫描二次置 5（单胜出防双头）");
        assertEquals(7, execTaskService.get(task.id()).status(), "胜出方=cancel，终态保持 7 不漂移");
    }


    @Test
    void 心跳待终止清单_恰一次下发后置通知位() {
        long exId = registerExecutor("ex-cancel-5", 2);
        ExecTaskInfo task = runningTask(exId);
        execTaskService.cancel(1L, task.id());

        List<Long> first = execTaskService.pendingCancelsFor(exId);
        assertEquals(List.of(task.id()), first, "首次心跳必须收到 kill 指令");

        List<Long> second = execTaskService.pendingCancelsFor(exId);
        assertTrue(second.isEmpty(), "cancel_notified_at 置位后不得重复下发（防执行器重复 kill）");
    }

    @Test
    void 心跳待终止清单_只回本执行器名下已取消未通知任务() {
        long exA = registerExecutor("ex-cancel-a", 2);
        long exB = registerExecutor("ex-cancel-b", 2);
        ExecTaskInfo taskA = runningTask(exA);
        execTaskService.cancel(1L, taskA.id());

        assertTrue(execTaskService.pendingCancelsFor(exB).isEmpty(),
                "kill 指令只下发给实际持有进程的执行器");
    }


    @Test
    void 回传cancelled_执行中任务映射已取消终态() {
        long exId = registerExecutor("ex-cancel-6", 2);
        ExecTaskInfo task = runningTask(exId);

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "cancelled",
                null, "进程已按终止指令退出（exit code 143）", null, null, null, null));

        ExecTaskInfo after = execTaskService.get(task.id());
        assertEquals(7, after.status(), "V11：executor kill 后回传 cancelled → 7 已取消");
    }

    @Test
    void 回传cancelled_已终止任务幂等零漂移() {
        long exId = registerExecutor("ex-cancel-7", 2);
        ExecTaskInfo task = runningTask(exId);
        execTaskService.cancel(1L, task.id());

        execTaskService.submitResult(new ExecResultInput(task.id(), task.claimToken(), "cancelled",
                null, "进程已按终止指令退出（exit code 143）", null, null, null, null));

        ExecTaskInfo after = execTaskService.get(task.id());
        assertEquals(7, after.status());
        assertTrue(after.errorDetail().contains("取消") || after.errorDetail().contains("终止"),
                "已取消任务回传不得覆盖终止人话原因：" + after.errorDetail());
    }


    @Test
    void 批量取消_部分成功语义已终态进拒绝清单() {
        long exId = registerExecutor("ex-cancel-8", 4);
        ExecTaskInfo t1 = runningTask(exId);
        ExecTaskInfo t2 = runningTask(exId);
        execTaskService.cancel(1L, t2.id());

        BatchCancelResult result = execTaskService.batchCancel(1L, List.of(t1.id(), t2.id(), 999999999L));

        assertEquals(1, result.cancelled(), "仅执行中任务成功终止");
        assertEquals(List.of(t2.id(), 999999999L), result.rejectedTaskIds(),
                "已终态与不存在任务进拒绝清单（部分成功不整单失败）");
        assertEquals(7, execTaskService.get(t1.id()).status());
    }

    @Test
    void 批量取消_超上限500_人话拒绝() {
        List<Long> over = java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList();
        ServiceException err = assertThrows(ServiceException.class,
                () -> execTaskService.batchCancel(1L, over));
        assertTrue(err.getMessage().contains("500"), "上限人话指引：" + err.getMessage());
    }


    @Test
    void 进程视图_在跑任务清单带时长与预算同源() {
        long exId = registerExecutor("ex-cancel-9", 4);
        ExecTaskInfo t1 = runningTask(exId);
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createApprovedCase(pid);
        execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));

        PageResult<RunningTaskView> page = execTaskService.listRunning(1, 20);

        assertEquals(1, page.total(), "进程视图=跨批次仅执行中(2)任务");
        RunningTaskView row = page.list().get(0);
        assertEquals(t1.id(), row.taskId());
        assertEquals(exId, row.executorId());
        assertNotNull(row.caseTitle(), "清单必须带用例名（进程视图列：用例/批次/执行器/开始/时长/预算）");
        assertNotNull(row.startedAt());
        assertTrue(row.elapsedMs() >= 0);
        assertTrue(row.timeoutMs() >= 60_000, "预算 timeoutMs 必须与看门狗同源非零：" + row.timeoutMs());
        assertEquals(0, row.timeoutMs() % 60_000);
    }

    @Test
    void 进程视图_取消后不再出现() {
        long exId = registerExecutor("ex-cancel-10", 2);
        ExecTaskInfo t = runningTask(exId);
        execTaskService.cancel(1L, t.id());

        PageResult<RunningTaskView> page = execTaskService.listRunning(1, 20);
        assertEquals(0, page.total(), "已取消任务退出进程视图");
    }


    @Test
    void V81迁移_cancel_notified_at列在位() {
        Integer cols = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns " +
                "WHERE table_name = 'tp_exec_task' AND column_name = 'cancel_notified_at'",
                Integer.class);
        assertEquals(1, cols, "V81：tp_exec_task.cancel_notified_at 必须手动落 dev 库（Flyway 未启用）");
    }
}
