package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
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
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.prod-blacklist=*.example.com")
class ExecTaskBrowserTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local/login\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectEnvService projectEnvService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown : the name-family sweep resolves
    // this suite's fixed-name project and walks the FK graph (case steps,
    // cases, envs, membership; tp_exec_task is in the helper's default
    // project-keyed set). The executor drops by its exact registered name.
    // A blanket TRUNCATE would wipe concurrent suites' rows on the dev database.
    private static final java.util.List<String> FIXTURE_PROJECT_NAMES =
            java.util.List.of("浏览器矩阵项目");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, java.util.List.of(), FIXTURE_PROJECT_NAMES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name = 'ex-browser'");
    }

    private long createProject() {
        return projectService.create(1L, new ProjectCreateInput("浏览器矩阵项目",
                "BR" + System.nanoTime() % 100000, "zh-CN", "P1", null));
    }

    private long createCase(long projectId) {
        return caseService.create(1L, new CaseCreateInput(projectId, "矩阵用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, java.util.List.of())).id();
    }

    private long createEnv(long projectId, String browsers) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", browsers, "v1.0.0"));
    }

    private String browserColumn(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT browser FROM tp_exec_task WHERE id = ?", String.class, taskId);
    }

    @Test
    void create_未指定浏览器_缺省chromium落列() {
        long pid = createProject();
        long envId = createEnv(pid, "chromium,firefox");
        long caseId = createCase(pid);

        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, caseId, "v2", ACTION_JSON, envId));

        assertEquals("chromium", browserColumn(taskId), "未指定=缺省 chromium（存量零漂移）");
    }

    @Test
    void create_浏览器在环境矩阵内_校验通过落列() {
        long pid = createProject();
        long envId = createEnv(pid, "chromium,firefox");
        long caseId = createCase(pid);

        long taskId = execTaskService.create(new ExecTaskCreateInput(
                pid, caseId, "v2", ACTION_JSON, envId, null, null, "firefox"));

        assertEquals("firefox", browserColumn(taskId));
    }

    @Test
    void create_浏览器超出环境矩阵_failClosed人话拒绝() {
        long pid = createProject();
        long envId = createEnv(pid, "chromium");
        long caseId = createCase(pid);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> execTaskService.create(new ExecTaskCreateInput(
                        pid, caseId, "v2", ACTION_JSON, envId, null, null, "firefox")));
        assertTrue(ex.getMessage().contains("firefox") && ex.getMessage().contains("环境"),
                "失败消息须点名浏览器与环境矩阵： " + ex.getMessage());
    }

    @Test
    void create_非法浏览器值_白名单外拒绝() {
        long pid = createProject();
        long envId = createEnv(pid, "chromium,firefox,webkit");
        long caseId = createCase(pid);

        assertThrows(ServiceException.class,
                () -> execTaskService.create(new ExecTaskCreateInput(
                        pid, caseId, "v2", ACTION_JSON, envId, null, null, "safari")));
    }

    @Test
    void claim_响应尾追browser字段_与落列同值下发() {
        long pid = createProject();
        long envId = createEnv(pid, "chromium,firefox");
        long caseId = createCase(pid);
        long taskId = execTaskService.create(new ExecTaskCreateInput(
                pid, caseId, "v2", ACTION_JSON, envId, null, null, "firefox"));
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-browser", null, null, "web", "chromium,firefox", 2, "1.0.0", 0));

        ClaimTaskView view = execTaskService.claim(exId);

        assertNotNull(view, "执行器必须领取到任务");
        assertEquals(taskId, view.taskId());
        assertEquals("firefox", view.browser(), "claim 必须按任务落列值下发 browser");
    }
}
