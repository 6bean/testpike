package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchUpdateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class BatchBrowserTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local/login\"}}]}";

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
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (truncate-ratchet discipline, same shape as the
    // appauto dispatch suite): projects live in the name family below; the FK
    // child-first sweep covers tp_script/tp_test_case/tp_project_env/
    // tp_user_project/tp_exec_task/tp_exec_batch under them. tp_batch_case has
    // no FK and no project column, so this family's rows purge first through
    // the still-present batch rows. A blanket TRUNCATE would wipe rows owned
    // by concurrent suites and L3 probes on the shared dev database.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "浏览器批BB";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_batch_case WHERE batch_id IN"
                + " (SELECT b.id FROM tp_exec_batch b"
                + "  JOIN tp_project p ON p.id = b.project_id"
                + "  WHERE p.name LIKE ?)", FIXTURE_PROJECT_NAME_PREFIX + "%");
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, java.util.List.of(),
                java.util.List.of(FIXTURE_PROJECT_NAME_PREFIX));
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("浏览器批" + code, code, "zh-CN", "P1", null));
    }

    private long createEnv(long pid, String browsers) {
        return projectEnvService.create(pid, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", browsers, "v1.0.0"));
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
        scriptService.save(new ScriptSaveInput(caseId, "v2", ACTION_JSON, 2));
    }

    private String batchBrowserColumn(long batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT browser FROM tp_exec_batch WHERE id = ?", String.class, batchId);
    }

    private String taskBrowserOfBatch(long batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT browser FROM tp_exec_task WHERE batch_id = ?", String.class, batchId);
    }

    @Test
    void create_指定浏览器在矩阵内_落批次列并回显() {
        long pid = createProject("BB1");
        long envId = createEnv(pid, "chromium,firefox");
        long caseId = createCase(pid, "用例1");

        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "firefox批", envId,
                List.of(caseId), null, null, null, "firefox"));

        assertEquals("firefox", batch.browser(), "创建响应回显批次浏览器");
        assertEquals("firefox", batchBrowserColumn(batch.id()), "browser 真实落批次列");
    }

    @Test
    void create_未指定浏览器_批次列为空存量零漂移() {
        long pid = createProject("BB2");
        long envId = createEnv(pid, "chromium,firefox");
        long caseId = createCase(pid, "用例1");

        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "无浏览器批", envId,
                List.of(caseId), null));

        assertNull(batch.browser(), "旧构造（未指定）=null 存量零变化");
        assertNull(batchBrowserColumn(batch.id()));
    }

    @Test
    void create_浏览器超出环境矩阵_failClosed人话拒绝() {
        long pid = createProject("BB3");
        long envId = createEnv(pid, "chromium");
        long caseId = createCase(pid, "用例1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.create(1L, new BatchCreateInput(pid, "超矩阵批", envId,
                        List.of(caseId), null, null, null, "firefox")));
        assertTrue(ex.getMessage().contains("firefox") && ex.getMessage().contains("矩阵"),
                "失败消息点名浏览器与环境矩阵: " + ex.getMessage());
    }

    @Test
    void create_非法内核名_白名单拒绝() {
        long pid = createProject("BB4");
        long envId = createEnv(pid, "chromium,firefox");
        long caseId = createCase(pid, "用例1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.create(1L, new BatchCreateInput(pid, "非法内核批", envId,
                        List.of(caseId), null, null, null, "edge")));
        assertTrue(ex.getMessage().contains("edge") && ex.getMessage().contains("chromium"),
                "白名单外点名取值与合法集: " + ex.getMessage());
    }

    @Test
    void update_草稿编辑改浏览器_落列与null不改() {
        long pid = createProject("BB5");
        long envId = createEnv(pid, "chromium,firefox,webkit");
        long caseId = createCase(pid, "用例1");
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "编辑批", envId,
                List.of(caseId), null, null, null, "firefox"));

        batchService.update(1L, batch.id(), new BatchUpdateInput(null, null, null, null, null, null, "webkit"));
        assertEquals("webkit", batchBrowserColumn(batch.id()), "编辑指定=更新");

        batchService.update(1L, batch.id(), new BatchUpdateInput("改名", null, null, null));
        assertEquals("webkit", batchBrowserColumn(batch.id()), "browser=null=不改（与其它字段同口径）");
    }

    @Test
    void start_批次浏览器_透传逐条任务列() {
        long pid = createProject("BB6");
        long envId = createEnv(pid, "chromium,firefox");
        long caseId = createCase(pid, "用例1");
        approve(caseId);
        withScript(caseId);
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "透传批", envId,
                List.of(caseId), null, null, null, "firefox"));

        batchService.start(1L, batch.id());

        assertEquals("firefox", taskBrowserOfBatch(batch.id()),
                "启动建任务链路批次 browser 透传 tp_exec_task.browser");
    }

    @Test
    void start_未选浏览器批次_任务缺省chromium零漂移() {
        long pid = createProject("BB7");
        long envId = createEnv(pid, "chromium,firefox");
        long caseId = createCase(pid, "用例1");
        approve(caseId);
        withScript(caseId);
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "缺省批", envId,
                List.of(caseId), null));

        batchService.start(1L, batch.id());

        assertEquals("chromium", taskBrowserOfBatch(batch.id()),
                "批次未指定=任务缺省 chromium（存量行为不变）");
    }

    @Test
    void quickExecute_指定浏览器_临时批落列并透传任务() {
        long pid = createProject("BB8");
        long envId = createEnv(pid, "chromium,firefox");
        long caseId = createCase(pid, "用例1");
        approve(caseId);
        withScript(caseId);

        var result = batchService.quickExecute(1L, caseId, envId, "firefox");

        assertEquals("firefox", batchBrowserColumn(result.batchId()), "快速执行临时批 browser 落列");
        assertEquals("firefox", taskBrowserOfBatch(result.batchId()), "临时批 browser 透传任务");
    }
}
