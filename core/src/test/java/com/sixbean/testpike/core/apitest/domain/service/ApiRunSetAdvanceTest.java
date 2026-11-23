package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RunSetCreateInput;
import com.sixbean.testpike.core.apitest.domain.component.ApiRunSetAdvancer;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collection-run orchestration tests ; real dev DB + real executor
 * claim/result chain, no mocks — strict testing standard).
 * <p>Covers: create dispatches the first hop only (sequential hops, one in
 * flight); the three interruption spectra prune correctly (STOP freezes every
 * remaining hop, CONTINUE dispatches everything, CHAIN_DEP drops only the
 * dependent chain and keeps independent hops); suppressed children reach the
 * skipped terminal without an envelope; the set row closes with honest
 * passed/failed/skipped aggregates; a duplicated advance call never
 * double-dispatches the next hop (the FOR UPDATE + dispatched_at guard).
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiRunSetAdvanceTest {

    @Autowired
    private ApiRunSetService apiRunSetService;

    @Autowired
    private ApiRunSetAdvancer apiRunSetAdvancer;

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

    /** Creates a fixture project through the service path; sweeps a crashed
     * run's same-code leftover first, tracked for teardown. */
    private long createProject(String code) {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code));
        long id = projectService.create(1L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
        // Executor rows carry no project key: purge this suite's fixed name.
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name = 'ex-adv'");
    }

    /** login(extract token) → order(uses token) → health(independent) — the classic chain. */
    private Seed seedChain(String code, String mode) {
        long pid = createProject(code);
        ApiCollectionInfo col = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "订单域", null));
        apiRequestService.create(1L, new RequestSaveInput(col.id(), "登录", "GET", "/api/login",
                null, null, null,
                "[{\"type\":\"extract\",\"saveAs\":\"token\",\"path\":\"$.data.token\"}]"));
        apiRequestService.create(1L, new RequestSaveInput(col.id(), "查订单", "GET",
                "/api/order/{{VAR:token}}", null, null, null,
                "[{\"type\":\"status\",\"expect\":200}]"));
        apiRequestService.create(1L, new RequestSaveInput(col.id(), "健康", "GET", "/api/health",
                null, null, null, "[{\"type\":\"status\",\"expect\":200}]"));
        long envId = jdbcTemplate.queryForObject("INSERT INTO tp_project_env (project_id, name, "
                + "base_url) VALUES (?, ?, ?) RETURNING id", Long.class,
                pid, "env-" + code, "http://adv-" + code + ".test");
        ApiRunSetInfo set = apiRunSetService.create(1L,
                new RunSetCreateInput(col.id(), envId, "推进" + code, mode));
        return new Seed(set.id(), apiRunSetService.childRunIds(set.id()), pid);
    }

    private record Seed(long setId, List<Long> childRunIds, long projectId) {
    }

    private ClaimTaskView claimOne() {
        long executorId = executorService.register(new ExecutorRegisterInput(
                "ex-adv", null, null, "web", "chromium", 2, "v1", 0));
        ClaimTaskView view = execTaskService.claim(executorId);
        assertNotNull(view, "派发单必须可领取（零新通道，复用既有 claim 链）");
        return view;
    }

    private void submit(ClaimTaskView view, String result, Integer errorType, String errorDetail) {
        List<Map<String, Object>> snapshots = List.of(Map.of(
                "stepNo", 1, "kind", "api-test-run",
                "request", Map.of("method", "GET", "url", "http://adv.test/api"),
                "response", Map.of("status", 200),
                "assertions", List.of(Map.of("type", "status", "expect", 200,
                        "passed", "pass".equals(result)))));
        execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(), result,
                errorType, errorDetail, List.of(), List.of(), 100, null,
                null, null, null, snapshots, null));
    }

    private int taskCount(long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Integer.class, projectId);
    }

    private Map<String, Object> setRow(long setId) {
        return jdbcTemplate.queryForMap("SELECT status, passed_count AS passed, failed_count AS "
                + "failed, skipped_count AS skipped FROM tp_apitest_run_set WHERE id = ?", setId);
    }

    private int childStatus(long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM tp_apitest_run WHERE id = ?",
                Integer.class, runId);
    }

    @Test
    void test_create_dispatches_only_the_first_hop() {
        Seed seed = seedChain("A1", "CONTINUE_AND_SUMMARY");
        assertEquals(1, taskCount(seed.projectId()), "开账只派首跳，其余跳待上一跳终态后再派");
        ApiRunSetInfo set = apiRunSetService.get(seed.setId());
        assertEquals(1, set.status(), "已派发首跳=在跑 1");
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT dispatched_at FROM tp_apitest_run WHERE id = ?", java.util.Date.class,
                seed.childRunIds().get(0)), "首跳子行打派发戳");
        assertNull(jdbcTemplate.queryForObject(
                "SELECT dispatched_at FROM tp_apitest_run WHERE id = ?", java.util.Date.class,
                seed.childRunIds().get(1)), "次跳未派发=无信封无戳");
    }

    @Test
    void test_stop_mode_freezes_every_remaining_hop() {
        Seed seed = seedChain("A2", "STOP_ON_ASSERT_FAIL");
        submit(claimOne(), "fail", 1, "断言不满足：实际 status=404 期望 status=200");

        assertEquals(4, childStatus(seed.childRunIds().get(1)), "STOP：后续跳直接终态跳过（不建信封）");
        assertEquals(4, childStatus(seed.childRunIds().get(2)));
        assertEquals(1, taskCount(seed.projectId()), "被抑制的跳绝不派发（双打被测=事故）");
        Map<String, Object> row = setRow(seed.setId());
        assertEquals(4, ((Number) row.get("status")).intValue(), "三档触发中断=集终态 4");
        assertEquals(0, ((Number) row.get("passed")).intValue());
        assertEquals(1, ((Number) row.get("failed")).intValue());
        assertEquals(2, ((Number) row.get("skipped")).intValue());
    }

    @Test
    void test_continue_mode_dispatches_all_and_summarizes() {
        Seed seed = seedChain("A3", "CONTINUE_AND_SUMMARY");
        submit(claimOne(), "fail", 1, "断言不满足：实际 status=404 期望 status=200");
        assertEquals(2, taskCount(seed.projectId()), "CONTINUE：失败不冻结在链");
        submit(claimOne(), "pass", null, null);
        assertEquals(3, taskCount(seed.projectId()));
        submit(claimOne(), "pass", null, null);

        Map<String, Object> row = setRow(seed.setId());
        assertEquals(3, ((Number) row.get("status")).intValue(), "有败收口=终态 3");
        assertEquals(2, ((Number) row.get("passed")).intValue());
        assertEquals(1, ((Number) row.get("failed")).intValue());
        assertEquals(0, ((Number) row.get("skipped")).intValue());
    }

    @Test
    void test_chain_dep_prunes_dependents_and_keeps_independent() {
        Seed seed = seedChain("A4", "CHAIN_DEP_ONLY");
        submit(claimOne(), "fail", 3, "连接失败：被测不可达");

        assertEquals(4, childStatus(seed.childRunIds().get(1)), "依赖失败跳产键的后续=终态跳过");
        assertEquals(2, taskCount(seed.projectId()), "无依赖的健康跳照常派发");
        submit(claimOne(), "pass", null, null);

        Map<String, Object> row = setRow(seed.setId());
        assertEquals(4, ((Number) row.get("status")).intValue(), "存在被抑制跳=按中断收口 4");
        assertEquals(1, ((Number) row.get("passed")).intValue());
        assertEquals(1, ((Number) row.get("failed")).intValue());
        assertEquals(1, ((Number) row.get("skipped")).intValue());
    }

    @Test
    void test_duplicate_advance_never_double_dispatches() {
        Seed seed = seedChain("A5", "CONTINUE_AND_SUMMARY");
        submit(claimOne(), "pass", null, null);
        int after = taskCount(seed.projectId());
        assertTrue(after >= 2, "首跳终态后次跳应已派发");

        // Simulate the double-callback race: the same finished hop drives advance again.
        apiRunSetAdvancer.onChildFinished(seed.childRunIds().get(0));
        assertEquals(after, taskCount(seed.projectId()), "重复推进=只推一次（FOR UPDATE+派发戳双闸）");
    }

    @Test
    void test_run_without_env_stays_queued_without_dispatch() {
        long pid = createProject("A6");
        ApiCollectionInfo col = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "空跑域", null));
        apiRequestService.create(1L, new RequestSaveInput(col.id(), "健康", "GET", "/api/health",
                null, null, null, "[]"));
        ApiRunSetInfo set = apiRunSetService.create(1L,
                new RunSetCreateInput(col.id(), null, "未选环境", "STOP_ON_ASSERT_FAIL"));
        assertEquals(0, set.status(), "未选环境=排队 0 不派发（同单请求口径：白名单未定禁派发）");
        assertEquals(0, taskCount(pid));
    }
}
