package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.api.dto.SessionArtifactsInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class SessionStickyClaimTest {

    
    private static final String ACTION_PORTABLE =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";
    
    private static final String ACTION_STICKY =
            "{\"version\":\"v2\",\"session\":{\"key\":\"login-a\",\"portability\":\"sticky\"},"
                    + "\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private CaseService caseService;

    // Fixture-scoped teardown : sweep this suite's fixed-name
    // projects through the shared FK-tree helper. tp_batch_context has no
    // project_id column and no FK into tp_exec_batch, so its rows are
    // pre-deleted by a batch-id subquery BEFORE the sweep; tp_resource_lease
    // carries project_id without an FK and rides the extras list. Executors
    // drop by their exact registered names. Blanket TRUNCATE would wipe
    // concurrent suites' rows on the shared dev database.
    private static final List<String> FIXTURE_PROJECT_NAMES =
            List.of("sticky亲和项目", "portable搬运项目", "防重放项目", "租约过期项目", "试运行回注项目");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        java.util.List<Long> pids =
                com.sixbean.testpike.core.support.SharedDbFixtureSweep
                        .projectIdsByNamePrefixes(jdbcTemplate, FIXTURE_PROJECT_NAMES);
        String idList = pids.isEmpty() ? "NULL" : pids.stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        jdbcTemplate.update("DELETE FROM tp_batch_context WHERE batch_id IN "
                + "(SELECT id FROM tp_exec_batch WHERE project_id IN (" + idList + "))");
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectList(jdbcTemplate,
                List.of("tp_resource_lease"), pids);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name IN "
                + "('ex-a','ex-b','ex-a2','ex-b2','ex-a3','ex-a4','ex-b4','ex-a5')");
    }

    private long createProject(String name) {
        return projectService.create(1L, new ProjectCreateInput(name,
                "P" + System.nanoTime() % 100000, "zh-CN", "P1", null));
    }

    private long createEnv(long projectId) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
    }

    private long createCase(long projectId, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title,
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    private long createBatch(long projectId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type, schedule_type)
                VALUES (?, ?, '会话搬运批', 2, 1, 0) RETURNING id
                """, Long.class, projectId, "B-sess-" + System.nanoTime() % 100000);
    }

    private long createTask(long projectId, long envId, long caseId, Long batchId, String actionJson) {
        return execTaskService.create(new ExecTaskCreateInput(projectId, caseId, "v2",
                actionJson, envId, batchId));
    }

    private long registerExecutor(String name) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 4, "1.0.0", 0));
    }

    @Test
    void sticky回注后非持有者跳过_持有者可续领() {
        long pid = createProject("sticky亲和项目");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "登录会话用例");
        long batch = createBatch(pid);
        createTask(pid, envId, caseId, batch, ACTION_STICKY);
        createTask(pid, envId, caseId, batch, ACTION_STICKY);

        long execA = registerExecutor("ex-a");
        long execB = registerExecutor("ex-b");

        ClaimTaskView first = execTaskService.claim(execA);
        assertNotNull(first, "A 机首任务必须可领");
        execTaskService.ingestSessionArtifacts(new SessionArtifactsInput(
                first.taskId(), first.claimToken(), Map.of("cookie", "abc123")));

        assertNull(execTaskService.claim(execB),
                "sticky 批候选必须被非持有者执行器跳过排队（claim 正常返回 null 禁异常）");
        ClaimTaskView second = execTaskService.claim(execA);
        assertNotNull(second, "sticky 批候选持有者必须优先可领");
        assertNotEquals(first.taskId(), second.taskId(), "持有者应领到第二任务而非重复首任务");
    }

    @Test
    void portable回注后异机可领且信封携工件() {
        long pid = createProject("portable搬运项目");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "跨机续跑用例");
        long batch = createBatch(pid);
        createTask(pid, envId, caseId, batch, ACTION_PORTABLE);
        createTask(pid, envId, caseId, batch, ACTION_PORTABLE);

        long execA = registerExecutor("ex-a2");
        long execB = registerExecutor("ex-b2");

        ClaimTaskView first = execTaskService.claim(execA);
        assertNotNull(first);
        execTaskService.ingestSessionArtifacts(new SessionArtifactsInput(
                first.taskId(), first.claimToken(), Map.of("token", "tk-9")));

        ClaimTaskView onB = execTaskService.claim(execB);
        assertNotNull(onB, "portable 批=自由调度，异机必须可领");
        assertEquals("tk-9", onB.sessionArtifacts().get("token"),
                "B 机领取信封必须携带 A 机回注的会话工件（跨机禁重复登录）");
    }

    @Test
    void 回注令牌不符拒绝且不落工件() {
        long pid = createProject("防重放项目");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "令牌校验用例");
        long batch = createBatch(pid);
        long taskId = createTask(pid, envId, caseId, batch, ACTION_PORTABLE);

        long execA = registerExecutor("ex-a3");
        ClaimTaskView first = execTaskService.claim(execA);
        assertNotNull(first);
        assertEquals(taskId, first.taskId());

        ServiceException e = assertThrows(ServiceException.class,
                () -> execTaskService.ingestSessionArtifacts(new SessionArtifactsInput(
                        taskId, "stolen-token", Map.of("cookie", "x"))));
        assertTrue(e.getMessage().contains("令牌"), "人话点名令牌不匹配: " + e.getMessage());
        Long ctxRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_context WHERE batch_id = ?", Long.class, batch);
        assertEquals(0L, ctxRows, "拒收后不得留半截工件");
    }

    @Test
    void sticky租约过期后非持有者恢复自由调度() {
        long pid = createProject("租约过期项目");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "掉线抢占用例");
        long batch = createBatch(pid);
        createTask(pid, envId, caseId, batch, ACTION_STICKY);
        createTask(pid, envId, caseId, batch, ACTION_STICKY);

        long execA = registerExecutor("ex-a4");
        long execB = registerExecutor("ex-b4");
        ClaimTaskView first = execTaskService.claim(execA);
        execTaskService.ingestSessionArtifacts(new SessionArtifactsInput(
                first.taskId(), first.claimToken(), Map.of("cookie", "y")));
        assertNull(execTaskService.claim(execB), "过期前 B 机被闸");

        jdbcTemplate.update("UPDATE tp_resource_lease SET expires_at = now() - interval '1 hour'"
                + " WHERE kind = 'session'");
        assertNotNull(execTaskService.claim(execB), "租约过期后 B 机必须恢复可领（禁永久饿死）");
    }

    @Test
    void 试运行无批次回注拒人话() {
        long pid = createProject("试运行回注项目");
        long envId = createEnv(pid);
        long caseId = createCase(pid, "试运行用例");
        createTask(pid, envId, caseId, null, ACTION_PORTABLE);

        long execA = registerExecutor("ex-a5");
        ClaimTaskView first = execTaskService.claim(execA);
        assertNotNull(first);
        ServiceException e = assertThrows(ServiceException.class,
                () -> execTaskService.ingestSessionArtifacts(new SessionArtifactsInput(
                        first.taskId(), first.claimToken(), Map.of("cookie", "z"))));
        assertTrue(e.getMessage().contains("批次") || e.getMessage().contains("试运行"),
                "人话点名无批次不支持回注: " + e.getMessage());
    }
}
