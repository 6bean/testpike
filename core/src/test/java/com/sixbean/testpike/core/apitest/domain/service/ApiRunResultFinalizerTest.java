package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ApiRunResultFinalizerTest {

    @Autowired
    private ApiRunService apiRunService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Self-healing fixture project through the service path, tracked for teardown. */
    private long createFinalizerProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                projectIds, "项目" + code, code);
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, projectIds);
        // Executor rows carry no project key: purge this suite's fixed names.
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name IN "
                + "('ex-apitest', 'ex-apitest2', 'ex-apitest3')");
    }

 
    private ApiRunInfo seedDispatchedRun(String code) {
        long pid = createFinalizerProject(code);
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "订单域", null));
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(root.id(), "查订单",
                "GET", "/api/v1/orders", null, null, null,
                "[{\"type\":\"status\",\"expect\":200}]"));
        long envId = jdbcTemplate.queryForObject("INSERT INTO tp_project_env (project_id, name, "
                + "base_url) VALUES (?, ?, ?) RETURNING id", Long.class,
                pid, "env-" + code, "http://order-" + code + ".test");
        return apiRunService.create(1L, new RunCreateInput(req.id(), envId));
    }

    private ClaimTaskView claimOne() {
        long executorId = executorService.register(new ExecutorRegisterInput(
                "ex-apitest", null, null, "web", "chromium", 2, "v1", 0));
        ClaimTaskView view = execTaskService.claim(executorId);
        assertTrue(view != null, "派发单必须可领取（红线6 复用 claim 通道）");
        return view;
    }

    private void submit(ClaimTaskView view, String result, Integer errorType, String errorDetail,
                        List<Map<String, Object>> assertionRows, Integer durationMs) {
        submit(view, result, errorType, errorDetail, assertionRows, durationMs, null);
    }

    private void submit(ClaimTaskView view, String result, Integer errorType, String errorDetail,
                        List<Map<String, Object>> assertionRows, Integer durationMs,
                        Map<String, Object> vars) {
        List<Map<String, Object>> snapshots = List.of(Map.of(
                "stepNo", 1, "kind", "api-test-run",
                "request", Map.of("method", "GET", "url", "http://order.test/api/v1/orders"),
                "response", Map.of("status", 200),
                "assertions", assertionRows));
        execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(), result,
                errorType, errorDetail, List.of(), List.of(), durationMs, null,
                null, null, null, snapshots, vars));
    }

    private Map<String, Object> runRow(long runId) {
        return jdbcTemplate.queryForMap("SELECT status, duration_ms, assert_detail::text AS detail, "
                + "error_msg FROM tp_apitest_run WHERE id = ?", runId);
    }

    @Test
    void test_pass_result_maps_run_success_with_assert_rows() {
        ApiRunInfo run = seedDispatchedRun("W1F1");
        ClaimTaskView view = claimOne();

        submit(view, "pass", null, null,
                List.of(Map.of("type", "status", "expect", 200, "passed", true, "actual", 200)),
                1234);

        Map<String, Object> row = runRow(run.id());
        assertEquals(1, ((Number) row.get("status")).intValue(), "全过 → 运行状态 1");
        assertEquals(1234, ((Number) row.get("duration_ms")).intValue());
        assertTrue(String.valueOf(row.get("detail")).contains("\"passed\""),
                "断言逐行结果随快照回写留档: " + row.get("detail"));
        assertEquals("", row.get("error_msg"));
    }

    @Test
    void test_assertion_fail_maps_run_2_with_error_detail() {
        ApiRunInfo run = seedDispatchedRun("W1F2");
        ClaimTaskView view = claimOne();

        submit(view, "fail", 1, "断言不满足：实际 status=404 期望 status=200",
                List.of(Map.of("type", "status", "expect", 200, "passed", false, "actual", 404)),
                80);

        Map<String, Object> row = runRow(run.id());
        assertEquals(2, ((Number) row.get("status")).intValue(), "断言不符（error_type=1）→ 2");
        assertTrue(String.valueOf(row.get("error_msg")).contains("断言不满足"),
                "执行器人话原文透传: " + row.get("error_msg"));
    }

    @Test
    void test_gate_reject_maps_run_3() {
        ApiRunInfo run = seedDispatchedRun("W1F3");
        ClaimTaskView view = claimOne();

        submit(view, "fail", 3, "连接 http://order.test 失败（被测服务不可达）",
                List.of(Map.of("type", "status", "expect", 200, "passed", false)),
                50);

        assertEquals(3, ((Number) runRow(run.id()).get("status")).intValue(),
                "闸拒/连接失败（error_type=3）→ 3");
    }

    @Test
    void test_manual_skip_result_maps_run_4_honest_skip() {
        // an unsatisfied precondition answers manual_skip — the run
        // row must land on status 4 (same honest-skip ledger state the collection
        // advancer already writes for suppressed hops), never 2/3 fake verdicts.
        ApiRunInfo run = seedDispatchedRun("W1F4");
        ClaimTaskView view = claimOne();

        submit(view, "manual_skip", null,
                "前置条件不满足（field=status op=eq expect=200）——本跳诚实跳过，未发送请求",
                List.of(), 10);

        Map<String, Object> row = runRow(run.id());
        assertEquals(4, ((Number) row.get("status")).intValue(),
                "manual_skip → 运行状态 4（诚实跳过三态，禁冒充断言失败/执行错误）");
        assertTrue(String.valueOf(row.get("error_msg")).contains("前置条件"),
                "跳过人话原因透传: " + row.get("error_msg"));
    }

    @Test
    void test_duplicate_result_does_not_rewrite_run() {
        ApiRunInfo run = seedDispatchedRun("W1F4");
        ClaimTaskView view = claimOne();

        submit(view, "pass", null, null,
                List.of(Map.of("type", "status", "expect", 200, "passed", true, "actual", 200)), 100);
        submit(view, "fail", 1, "迟到重复包",
                List.of(Map.of("type", "status", "expect", 999, "passed", false, "actual", 200)), 999);

        Map<String, Object> row = runRow(run.id());
        assertEquals(1, ((Number) row.get("status")).intValue(), "首包胜出，重复包幂等不改写");
        assertEquals(100, ((Number) row.get("duration_ms")).intValue());
    }

    @Test
    void test_non_api_task_result_leaves_apitest_untouched() {
        long pid = createFinalizerProject("W1F5");
        long taskId = execTaskService.createExploreAct(pid,
                "{\"kind\":\"explore-act\",\"action\":\"page-snapshot\",\"url\":\"http://x.test\"}");
        long executorId = executorService.register(new ExecutorRegisterInput(
                "ex-apitest2", null, null, "web", "chromium", 2, "v1", 0));
        ClaimTaskView view = execTaskService.claim(executorId);
        assertTrue(view != null && view.taskId() == taskId);

        submit(view, "pass", null, null,
                List.of(Map.of("type", "status", "expect", 200, "passed", true, "actual", 200)), 10);

        Integer runs = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_apitest_run r "
                        + "JOIN tp_apitest_request q ON q.id = r.request_id "
                        + "JOIN tp_apitest_collection c ON c.id = q.collection_id "
                        + "WHERE c.project_id = ?",
                Integer.class, pid);
        assertEquals(0, runs, "非 api-test-run 任务回传零触碰运行表");
    }

    @Test
    void test_vars_roundtrip_pools_collection_and_seeds_sibling_request() throws Exception {
        ApiRunInfo run = seedDispatchedRun("W1F7");
        ClaimTaskView view = claimOne();

        submit(view, "pass", null, null,
                List.of(Map.of("type", "status", "expect", 200, "passed", true, "actual", 200)),
                100, Map.of("TOKEN", "chain-1"));

        Long collectionId = jdbcTemplate.queryForObject(
                "SELECT q.collection_id FROM tp_apitest_request q "
                        + "JOIN tp_apitest_run r ON r.request_id = q.id WHERE r.id = ?",
                Long.class, run.id());
        String stored = jdbcTemplate.queryForObject(
                "SELECT vars_json::text FROM tp_apitest_collection WHERE id = ?",
                String.class, collectionId);
        assertTrue(stored.contains("TOKEN"), "提取值必须入集合级池: " + stored);

        Long envId = jdbcTemplate.queryForObject(
                "SELECT env_id FROM tp_apitest_run WHERE id = ?", Long.class, run.id());
        ApiRequestInfo sibling = apiRequestService.create(1L, new RequestSaveInput(
                collectionId, "建订单", "POST", "/api/v1/orders", null, null, null,
                "[{\"type\":\"status\",\"expect\":201}]"));
        apiRunService.create(1L, new RunCreateInput(sibling.id(), envId));
        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE task_type = 4 AND project_id = ("
                        + "SELECT c.project_id FROM tp_apitest_collection c "
                        + "JOIN tp_apitest_request q ON q.collection_id = c.id "
                        + "JOIN tp_apitest_run r ON r.request_id = q.id WHERE r.id = ?) "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, run.id());
        com.fasterxml.jackson.databind.JsonNode env =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(actionJson);
        assertEquals("chain-1", env.path("vars").path("TOKEN").asText(),
                "前序提取值应作为同集合兄弟请求的信封 vars 种子（解密明文，链式传参闭环）");
    }

    @Test
    void test_global_prefixed_key_routes_to_project_table_across_collections() throws Exception {
        ApiRunInfo run = seedDispatchedRun("W1F8");
        ClaimTaskView view = claimOne();

        submit(view, "pass", null, null,
                List.of(Map.of("type", "status", "expect", 200, "passed", true, "actual", 200)),
                100, Map.of("global.TENANT", "t-9"));

        String stored = jdbcTemplate.queryForObject(
                "SELECT c.vars_json::text FROM tp_apitest_request q "
                        + "JOIN tp_apitest_collection c ON c.id = q.collection_id "
                        + "JOIN tp_apitest_run r ON r.request_id = q.id WHERE r.id = ?",
                String.class, run.id());
        assertTrue(!stored.contains("TENANT"), "global 键不得留在集合池: " + stored);
        Long globalRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_apitest_global_var g WHERE g.var_key = 'TENANT' "
                        + "AND g.project_id = ("
                        + "SELECT c.project_id FROM tp_apitest_collection c "
                        + "JOIN tp_apitest_request q ON q.collection_id = c.id "
                        + "JOIN tp_apitest_run r ON r.request_id = q.id WHERE r.id = ?)",
                Long.class, run.id());
        assertEquals(1L, globalRows, "global.TENANT 应剥前缀 UPSERT 进项目级表");

        Long projectId = jdbcTemplate.queryForObject(
                "SELECT c.project_id FROM tp_apitest_collection c "
                        + "JOIN tp_apitest_request q ON q.collection_id = c.id "
                        + "JOIN tp_apitest_run r ON r.request_id = q.id WHERE r.id = ?",
                Long.class, run.id());
        Long envId = jdbcTemplate.queryForObject(
                "SELECT env_id FROM tp_apitest_run WHERE id = ?", Long.class, run.id());
        ApiCollectionInfo other = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "结算域", null));
        ApiRequestInfo reqB = apiRequestService.create(1L, new RequestSaveInput(other.id(),
                "查账单", "GET", "/api/v1/bills", null, null, null,
                "[{\"type\":\"status\",\"expect\":200}]"));
        apiRunService.create(1L, new RunCreateInput(reqB.id(), envId));
        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE task_type = 4 AND project_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, projectId);
        com.fasterxml.jackson.databind.JsonNode env =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(actionJson);
        assertEquals("t-9", env.path("vars").path("TENANT").asText(),
                "global 键应跨集合以裸名进入信封种子（解密明文）");
    }

    @Test
    void test_envelope_without_runid_does_not_break_result_leg() {
        long pid = createFinalizerProject("W1F6");
        Long taskId = jdbcTemplate.queryForObject("INSERT INTO tp_exec_task (project_id, case_id, "
                + "status, schema_version, action_json, task_type) VALUES (?, 0, 1, 'apitest-v1', ?, 4) "
                + "RETURNING id", Long.class, pid,
                "{\"kind\":\"api-test-run\",\"method\":\"GET\",\"urlPath\":\"/x\"}");
        long executorId = executorService.register(new ExecutorRegisterInput(
                "ex-apitest3", null, null, "web", "chromium", 2, "v1", 0));
        ClaimTaskView view = execTaskService.claim(executorId);
        assertTrue(view != null && view.taskId() == taskId);

        submit(view, "pass", null, null, List.of(Map.of("type", "status", "expect", 200,
                "passed", true, "actual", 200)), 10);

        String status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM tp_exec_task WHERE id = ?", String.class, taskId);
        assertEquals("3", status, "缺 runId 脏信封：任务正常收口 3，映射链路静默跳过不炸链");
    }
}
