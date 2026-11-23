package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.ResponseCompareView;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;
import com.sixbean.testpike.core.apitest.domain.component.ResponseCompareMode;
import com.sixbean.testpike.core.apitest.domain.component.ResponseDiffKind;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run-history response compare (W-c5). Real chain, no mocks: create run →
 * dispatch → claim → submitResult with protocol snapshots → compare reads the
 * snapshots back through the shared gunzip path. Three comparison modes are
 * pinned here: json (four-color leaf diff), text (line-level diff) and binary
 * (sha-256 digest only, never raw bytes).
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiRunCompareTest {

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

    private final java.util.List<Long> compareTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, compareTrackedProjects);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-cmp-%'");
    }

    /** Seed project + root collection + one GET request + env, then create a dispatched run. */
    private ApiRunInfo seedDispatchedRun(String code) {
        long pid = ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                compareTrackedProjects, "项目" + code, code);
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

    /** Dispatch the pending run and submit a result carrying one api-test-run response snapshot. */
    private void dispatchWithBody(ApiRunInfo run, String body) {
        ClaimTaskView view = execTaskService.claim(executorService.register(
                new ExecutorRegisterInput("ex-cmp-" + run.id(), null, null, "web", "chromium",
                        2, "v1", 0)));
        assertNotNull(view, "派发单必须可领取（复用 claim 通道）");
        List<Map<String, Object>> snapshots = List.of(Map.of(
                "stepNo", 1, "kind", "api-test-run",
                "request", Map.of("method", "GET", "url", "http://order.test/api/v1/orders"),
                "response", Map.of("status", 200, "url", "http://order.test/api/v1/orders",
                        "headers", Map.of("content-type", "application/json"), "body", body),
                "assertions", List.of(Map.of("type", "status", "expect", 200, "passed", true))));
        execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(),
                "pass", null, null, List.of(), List.of(), 10, null, null, null, null,
                snapshots, null));
    }

    @Test
    void test_json_bodies_compare_as_four_color_leaf_diff() {
        ApiRunInfo left = seedDispatchedRun("CMP1");
        dispatchWithBody(left, "{\"total\":1,\"name\":\"order\"}");
        ApiRunInfo right = seedDispatchedRun("CMP2");
        dispatchWithBody(right, "{\"total\":2,\"status\":\"NEW\"}");

        ResponseCompareView view = apiRunService.compare(left.id(), right.id());

        assertEquals(ResponseCompareMode.JSON, view.mode(), "两侧均可解析=JSON 结构对比谱");
        assertTrue(!view.leftTruncated() && !view.rightTruncated());
        assertEquals(3, view.entries().size(), "四色叶子差异逐条: " + view.entries());
        assertEquals(ResponseDiffKind.VALUE_CHANGED, kindAt(view, "$.total"));
        assertEquals(ResponseDiffKind.REMOVED, kindAt(view, "$.name"));
        assertEquals(ResponseDiffKind.ADDED, kindAt(view, "$.status"));
    }

    @Test
    void test_identical_json_bodies_produce_zero_entries() {
        ApiRunInfo left = seedDispatchedRun("CMP3");
        dispatchWithBody(left, "{\"a\":1,\"list\":[1,2]}");
        ApiRunInfo right = seedDispatchedRun("CMP4");
        dispatchWithBody(right, "{\"list\":[1,2],\"a\":1}");

        ResponseCompareView view = apiRunService.compare(left.id(), right.id());

        assertEquals(ResponseCompareMode.JSON, view.mode());
        assertTrue(view.entries().isEmpty(), "键序不同但值相同=零差异（数字与对象均按值比较）");
    }

    @Test
    void test_non_json_bodies_fall_back_to_line_level_text_diff() {
        ApiRunInfo left = seedDispatchedRun("CMP5");
        dispatchWithBody(left, "order created\namount=10\nok");
        ApiRunInfo right = seedDispatchedRun("CMP6");
        dispatchWithBody(right, "order created\namount=12\nok\nextra");

        ResponseCompareView view = apiRunService.compare(left.id(), right.id());

        assertEquals(ResponseCompareMode.TEXT, view.mode(), "不可解析且无 NUL=行级文本谱");
        assertEquals(2, view.entries().size(), "首尾公共行 trim 后只报差异行: " + view.entries());
        assertEquals(ResponseDiffKind.VALUE_CHANGED, kindAt(view, "line[1]"));
        assertEquals(ResponseDiffKind.ADDED, kindAt(view, "line[3]"));
    }

    @Test
    void test_binary_bodies_report_digest_only_without_entries() {
        ApiRunInfo left = seedDispatchedRun("CMP7");
        dispatchWithBody(left, "PK\u0000abc");
        ApiRunInfo right = seedDispatchedRun("CMP8");
        dispatchWithBody(right, "PK\u0000abd");

        ResponseCompareView view = apiRunService.compare(left.id(), right.id());

        assertEquals(ResponseCompareMode.BINARY, view.mode(), "含 U+0000=二进制谱");
        assertTrue(view.entries().isEmpty(), "二进制两侧禁渲染字节差异");
        assertNotNull(view.leftSha256());
        assertNotNull(view.rightSha256());
        assertTrue(!view.leftSha256().equals(view.rightSha256()), "内容不同则摘要不同");
        assertEquals(64, view.leftSha256().length(), "SHA-256 十六进制摘要");
    }

    @Test
    void test_unknown_run_side_throws_not_found() {
        ApiRunInfo right = seedDispatchedRun("CMP9");
        dispatchWithBody(right, "{\"a\":1}");

        ServiceException e = assertThrows(ServiceException.class,
                () -> apiRunService.compare(9999999L, right.id()));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode(), "任一侧运行不存在=404 语义");
    }

    private ResponseDiffKind kindAt(ResponseCompareView view, String path) {
        return view.entries().stream()
                .filter(entry -> entry.path().equals(path))
                .map(entry -> entry.kind())
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少路径 " + path + " 的差异条目: "
                        + view.entries()));
    }
}
