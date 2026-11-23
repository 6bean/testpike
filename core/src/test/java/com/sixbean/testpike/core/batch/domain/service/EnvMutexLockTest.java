package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class EnvMutexLockTest {

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
    private StringRedisTemplate redis;
    @Autowired
    private com.sixbean.testpike.core.batch.domain.component.EnvExecutionLock envExecutionLock;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        // Fixture-scoped teardown (truncate-ratchet discipline, sub-batch H):
        // projects live in the name family below (codes E10*); the FK
        // child-first sweep covers tp_script/tp_test_case/tp_project_env/
        // tp_user_project and the project-keyed defaults tp_exec_task/
        // tp_exec_batch. tp_batch_case has no FK and no project column, so
        // this family's rows purge first; batch in-site notification rows
        // (no project FK on tp_notification) purge through the dedup-key
        // batch segment. The env-execution Redis locks purge ONLY for this
        // family's environments — the old blanket `lock:env-exec:*` delete
        // wiped other suites' live locks (the appauto suite pinned the same
        // rule); the product lease expires any residue on its own.
        // Executors purge by the 'ex-e10' name family. The old CASCADE
        // wiped shipped tp_user rows and every other suite's data.
        java.util.List<Long> familyIds =
                com.sixbean.testpike.core.support.SharedDbFixtureSweep.projectIdsByNamePrefixes(
                        jdbcTemplate, List.of("项目E10"));
        java.util.List<String> lockKeys = familyIds.isEmpty() ? List.of()
                : jdbcTemplate.queryForList(
                        "SELECT 'lock:env-exec:' || e.id FROM tp_project_env e"
                                + " WHERE e.project_id IN (" + familyIds.stream()
                                        .map(String::valueOf).collect(java.util.stream.Collectors.joining(","))
                                + ")", String.class);
        if (!lockKeys.isEmpty()) {
            redis.delete(lockKeys);
        }
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchCaseRowsOfProjects(
                jdbcTemplate, familyIds);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchNotificationsOfProjects(
                jdbcTemplate, familyIds);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of(), List.of("项目E10"));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-e10%'");
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

    
    private BatchInfo startedBatch(long pid, long env, String name, int caseCount) {
        List<Long> caseIds = new java.util.ArrayList<>();
        for (int i = 1; i <= caseCount; i++) {
            caseIds.add(createApprovedCase(pid, name + "用例" + i));
        }
        BatchInfo batch = batchService.create(1L,
                new BatchCreateInput(pid, name, env, caseIds, null));
        batchService.start(1L, batch.id());
        return batch;
    }

    private long registerExecutor(String name) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 2, "1.0.0", 0));
    }

    @Test
    void test_tc_e10_03_running_batch_locks_env_and_rival_waits() {
        long pid = createProject("E10A");
        long env = createEnv(pid, "互斥环境");
        BatchInfo b1 = startedBatch(pid, env, "批次1", 1);
        BatchInfo b2 = startedBatch(pid, env, "批次2", 1);

        long ex = registerExecutor("ex-e10a");
        assertNotNull(execTaskService.claim(ex), "批次1 首任务可领取");
        BatchInfo b1After = batchService.get(b1.id());
        assertEquals(4, b1After.status(), "领取后批次1=执行中(4)");
        String owner = redis.opsForHash().keys("lock:env-exec:" + env).stream()
                .findFirst().map(Object::toString).orElse(null);
        assertNotNull(owner, "环境锁已落 Redis（跨进程可见）");
        assertEquals(String.valueOf(b1.id()), owner, "锁 owner=批次1（TC-E10-03 占用方）");

        long ex2 = registerExecutor("ex-e10a2");
        assertNull(execTaskService.claim(ex2), "批次2 同环境任务排队等待（非并行执行）");
        assertEquals(2, batchService.get(b2.id()).status(), "排队批保持待执行(2)");
    }

    @Test
    void test_tc_e10_03_same_batch_reentrant_dispatch() {
        long pid = createProject("E10B");
        long env = createEnv(pid, "互斥环境");
        BatchInfo b1 = startedBatch(pid, env, "多任务批", 2);
        long ex = registerExecutor("ex-e10b");
        assertNotNull(execTaskService.claim(ex), "首任务领取");
        assertNotNull(execTaskService.claim(ex), "同批第二任务属主重入放行");
    }

    @Test
    void test_tc_e10_03_release_on_terminal_frees_rival() {
        long pid = createProject("E10C");
        long env = createEnv(pid, "互斥环境");
        BatchInfo b1 = startedBatch(pid, env, "被取消批", 1);
        BatchInfo b2 = startedBatch(pid, env, "接管批", 1);
        long ex = registerExecutor("ex-e10c");
        assertNotNull(execTaskService.claim(ex), "批次1 先占用环境");

        batchService.cancel(1L, b1.id());
        assertTrue(Boolean.FALSE.equals(redis.hasKey("lock:env-exec:" + env))
                || String.valueOf(b2.id()).equals(redis.opsForHash().keys("lock:env-exec:" + env)
                        .stream().findFirst().map(Object::toString).orElse(null)),
                "批次1 终态后锁释放或被接管");

        long ex2 = registerExecutor("ex-e10c2");
        assertNotNull(execTaskService.claim(ex2), "批次1 取消自动放行批次2");
        assertEquals(4, batchService.get(b2.id()).status(), "批次2 接管推进执行中");
    }

    @Test
    void test_release_by_batch_tolerates_null_env_id() {
        long pid = createProject("E10E");
        long env = createEnv(pid, "互斥环境");
        BatchInfo b1 = startedBatch(pid, env, "无环境释放批", 1);
        long ex = registerExecutor("ex-e10e");
        assertNotNull(execTaskService.claim(ex), "批次先占环境");
        jdbcTemplate.update("UPDATE tp_exec_batch SET env_id = NULL WHERE id = ?", b1.id());
        envExecutionLock.releaseByBatch(b1.id());
        assertNotNull(envExecutionLock, "无异常即通过（释放 NULL env 批静默幂等）");
    }

    @Test
    void test_tc_e10_03_done_release_via_finalize() {
        long pid = createProject("E10D");
        long env = createEnv(pid, "互斥环境");
        BatchInfo b1 = startedBatch(pid, env, "收口批", 1);
        BatchInfo b2 = startedBatch(pid, env, "等待批", 1);
        long ex = registerExecutor("ex-e10d");
        var claim = execTaskService.claim(ex);
        assertNotNull(claim, "批次1 占用环境");
        execTaskService.submitResult(new com.sixbean.testpike.core.executor.api.dto.ExecResultInput(
                claim.taskId(), claim.claimToken(), "pass", null, null, null, null, null, null));
        assertEquals(6, batchService.get(b1.id()).status(), "全任务终态收口=已完成(6)");
        long ex2 = registerExecutor("ex-e10d2");
        assertNotNull(execTaskService.claim(ex2), "批次1 完成自动放行批次2");
    }
}
