package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Runs against the shared dev database (SpringBootTest, no mocks). Teardown is
 * fixture-scoped per the truncate-ratchet discipline: the fixture-project name
 * family is swept FK child-first via {@code SharedDbFixtureSweep}, the
 * constraint-free {@code tp_batch_case} rows of this family's batches are dropped
 * first by a join (before their parent batch rows go away), and this suite's
 * executors purge by the {@code aa-ex-} name family. No blanket TRUNCATE.
 */
@SpringBootTest(classes = CoreApplication.class)
class BatchAppAutoDispatchTest {

    private static final String WEB_ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local/login\"}}]}";
    private static final String APP_ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}},"
                    + "{\"action\":\"app.tap\",\"target\":{\"resourceId\":\"com.demo:id/btn\"}}]}";
    /** One http.request step, the shape a promoted sandbox case carries . */
    private static final String HTTP_ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"http.request\",\"method\":\"GET\","
                    + "\"url\":\"http://demo.local/api/orders\",\"expect\":{\"status\":200}}]}";

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
    private com.sixbean.testpike.core.executor.domain.service.ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (truncate-ratchet discipline, same shape as the
    // executor claim sub-batches). The name-family sweep walks the FK graph
    // child-first, covering tp_script/tp_test_case/tp_project_env/
    // tp_user_project (under tp_project) and tp_batch_param_snapshot/
    // tp_batch_runtime_change (FK on tp_exec_batch, itself project-keyed).
    // tp_batch_case carries NO FK constraint and no project_id, so the generic
    // walk cannot see it: this family's rows drop first, joined through the
    // batch rows that still exist at that point (a crashed run's orphans of
    // the same family get re-swept here by the next @BeforeEach too).
    // A blanket TRUNCATE would wipe rows owned by concurrent suites and L3
    // probes on the shared dev database.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "APP派发批";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        java.util.List<Long> familyIds =
                com.sixbean.testpike.core.support.SharedDbFixtureSweep.projectIdsByNamePrefixes(
                        jdbcTemplate, java.util.List.of(FIXTURE_PROJECT_NAME_PREFIX));
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchCaseRowsOfProjects(
                jdbcTemplate, familyIds);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, java.util.List.of(),
                java.util.List.of(FIXTURE_PROJECT_NAME_PREFIX));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'aa-ex-%'");
    }

    
    private void registerCapableExecutor() {
        executorService.register(new com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput(
                "aa-ex-" + System.nanoTime(), null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", List.of("MOBILEWRIGHT"),
                        "platforms", List.of("ANDROID"),
                        "deviceKinds", List.of("EMULATOR"))));
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("APP派发批" + code, code, "zh-CN", "P1", null));
    }

    private long createEnv(long pid) {
        return projectEnvService.create(pid, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web,mobile", "chromium", "v1.0.0"));
    }

    private long createWebCase(long pid, String title) {
        return caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of())).id();
    }

    
    private long createAppCase(long pid, String title) {
        return caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "mobile", 3, 2, 1, null, List.of(), null, null,
                "app", "MOBILEWRIGHT", null)).id();
    }

    private void withScript(long caseId, String actionJson) {
        scriptService.save(new ScriptSaveInput(caseId, "v2", actionJson, 2));
    }

    private long startBatch(long pid, long envId, List<Long> caseIds) {
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(
                pid, "派发批", envId, caseIds, null));
        BatchStartResult result = batchService.start(1L, batch.id());
        this.lastStartMessage = result.message();
        return batch.id();
    }

    private String lastStartMessage;

    private Map<String, Object> appTaskOfBatch(long batchId) {
        return jdbcTemplate.queryForMap(
                "SELECT task_type, schema_version, case_id, batch_id, action_json "
                        + "FROM tp_exec_task WHERE batch_id = ? AND task_type = 6", batchId);
    }

    @Test
    void start_case挂app目标有app脚本_建task_type6任务挂批带真实用例id() {
        long pid = createProject("AA1");
        long envId = createEnv(pid);
        registerCapableExecutor();
        long caseId = createAppCase(pid, "登录页点击");
        withScript(caseId, APP_ACTION_JSON);

        long batchId = startBatch(pid, envId, List.of(caseId));

        Map<String, Object> task = appTaskOfBatch(batchId);
        assertEquals(6, ((Number) task.get("task_type")).intValue(), "APP 支 task_type=6 分流");
        assertEquals("appauto-v1", task.get("schema_version"));
        assertEquals(batchId, ((Number) task.get("batch_id")).longValue(),
                "坑A 钉死：批次派发必须挂批（finalize 计数+暂停过滤依赖 batch_id）");
        assertEquals(caseId, ((Number) task.get("case_id")).longValue(),
                "挂批任务必须带真实用例 id（结果回写/执行历史按 case_id 关联）");
        String actionJson = (String) task.get("action_json");
        assertTrue(actionJson.contains("\"app-auto-run\""), "信封 kind=app-auto-run: " + actionJson);
        assertTrue(actionJson.contains("MOBILEWRIGHT"), "引擎描述符投影随单下发");
        assertTrue(actionJson.contains("app.launch") && actionJson.contains("app.tap"),
                "steps 从脚本原样透传禁改写: " + actionJson);
    }

    @Test
    void start_混合批次_web支与app支互不干扰双计数() {
        long pid = createProject("AA2");
        long envId = createEnv(pid);
        registerCapableExecutor();
        long webCase = createWebCase(pid, "网页用例");
        withScript(webCase, WEB_ACTION_JSON);
        long appCase = createAppCase(pid, "APP用例");
        withScript(appCase, APP_ACTION_JSON);

        long batchId = startBatch(pid, envId, List.of(webCase, appCase));

        Integer webTasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND task_type <> 6",
                Integer.class, batchId);
        Integer appTasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND task_type = 6",
                Integer.class, batchId);
        assertEquals(1, webTasks, "web 支走既有建任务零漂移");
        assertEquals(1, appTasks, "app 支独立分流");
        assertNotNull(lastStartMessage);
        assertTrue(lastStartMessage.contains("2"), "message 计数含双支共 2 单: " + lastStartMessage);
    }

    @Test
    void start_挂app目标无app脚本_不烧会话人话跳过() {
        long pid = createProject("AA3");
        long envId = createEnv(pid);
        long caseId = createAppCase(pid, "未编排步骤");

        long batchId = startBatch(pid, envId, List.of(caseId));

        Integer appTasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ?", Integer.class, batchId);
        assertEquals(0, appTasks, "无 app.* 步骤=禁建空信封任务（不烧执行器会话）");
        assertTrue(lastStartMessage.contains("无脚本") || lastStartMessage.contains("步骤"),
                "跳过计数人话回显: " + lastStartMessage);
    }

    @Test
    void start_api目标用例带http脚本_建普通任务零未编排跳过() {
        // journey blocker: a promoted sandbox case (automation_target=api,
        // one http.request step) must dispatch through the regular webdriver task
        // (B11 inline http.request), never through the APP branch — the interception
        // SQL once swept api into appAutoTargetsOf, extractAppSteps returned empty,
        // and the batch silently built zero tasks with a plain-language "steps not
        // orchestrated" skip message instead.
        long pid = createProject("AA5");
        long envId = createEnv(pid);
        long caseId = caseService.create(1L, new CaseCreateInput(pid, "升格回归用例", "前置", "预期",
                1, 2, null, 3, 3, 6, "apitest:1", List.of(), null, null,
                "api", null, null)).id();
        withScript(caseId, HTTP_ACTION_JSON);

        long batchId = startBatch(pid, envId, List.of(caseId));

        Integer normalTasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ? AND task_type = 1",
                Integer.class, batchId);
        assertEquals(1, normalTasks, "api 目标用例必须落普通支建任务（webdriver 内联 http.request）");
        assertTrue(lastStartMessage.contains("已生成 1 个执行任务"),
                "批计数含该用例: " + lastStartMessage);
        assertFalse(lastStartMessage.contains("未编排"),
                "跳过人话不得点名该用例: " + lastStartMessage);
    }

    @Test
    void start_无匹配能力执行器_启动秒拒人话零任务落库() {
        long pid = createProject("AA4");
        long envId = createEnv(pid);
        long caseId = caseService.create(1L, new CaseCreateInput(pid, "秒拒观测", "前置", "预期",
                1, 2, "mobile", 3, 2, 1, null, List.of(), null, null,
                "harmony", "HMNEXTAUTO", null)).id();
        withScript(caseId, APP_ACTION_JSON);

        com.sixbean.testpike.common.exception.ServiceException ex =
                assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                        () -> startBatch(pid, envId, List.of(caseId)));
        assertTrue(ex.getMessage().contains("HMNEXTAUTO"), "拒因点名所需引擎: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("0 台"), "如实报可执行台数: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("{"), "拒因零 JSON dump: " + ex.getMessage());
        Integer appTasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ? AND task_type = 6",
                Integer.class, pid);
        assertEquals(0, appTasks, "秒拒=零落库（批次启动事务回滚，执行器上线后重开批次即可）");
    }
}
