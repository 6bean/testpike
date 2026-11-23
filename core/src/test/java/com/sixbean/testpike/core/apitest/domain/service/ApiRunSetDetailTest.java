package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetDetail;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetStep;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RunSetCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collection-run detail read-model tests .
 * <p>The detail shape is what the admin bridge and the frontend run-set panel
 * consume: ledger fields plus one row per child run, ordered by seq_no, joined
 * to the request row for name/method. Covers: step order + field fidelity,
 * status/duration/error passthrough after results land, 404 on a missing set,
 * and an honest empty step list for a ledger without children.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiRunSetDetailTest {

    @Autowired
    private ApiRunSetService apiRunSetService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> setDetailTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, setDetailTrackedProjects);
    }

    private long createDetailProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                setDetailTrackedProjects, "项目" + code, code);
    }

    private ApiCollectionInfo createCollection(long projectId, String name) {
        return apiCollectionService.create(1L, new CollectionSaveInput(projectId, 0L, name, null));
    }

    private ApiRequestInfo createRequest(long collectionId, String name, String method, String path) {
        return apiRequestService.create(1L, new RequestSaveInput(collectionId, name, method,
                path, null, null, null, null));
    }

    @Test
    void detail_子行按seq_no升序_请求名方法红绿灯全量回显() {
        long projectId = createDetailProject("DT1");
        ApiCollectionInfo collection = createCollection(projectId, "订单链路");
        ApiRequestInfo login = createRequest(collection.id(), "登录", "POST", "/auth/login");
        ApiRequestInfo order = createRequest(collection.id(), "下单", "POST", "/orders");
        ApiRequestInfo query = createRequest(collection.id(), "查单", "GET", "/orders/1");

        ApiRunSetInfo created = apiRunSetService.create(1L,
                new RunSetCreateInput(collection.id(), null, "冒烟一轮", "CONTINUE_AND_SUMMARY"));

        // Simulate results landing out-of-order (dispatch hop 2 finished before 1):
        // detail must still read back in seq_no order, not completion order.
        jdbcTemplate.update("UPDATE tp_apitest_run SET status = 1, duration_ms = 120 "
                + "WHERE request_id = ? AND run_set_id = ?", order.id(), created.id());
        jdbcTemplate.update("UPDATE tp_apitest_run SET status = 3, duration_ms = 800, "
                + "error_msg = 'connect refused' WHERE request_id = ? AND run_set_id = ?",
                login.id(), created.id());

        ApiRunSetDetail detail = apiRunSetService.detail(created.id());

        assertEquals(created.id(), detail.id());
        assertEquals("CONTINUE_AND_SUMMARY", detail.interruptionMode());
        assertEquals(collection.id(), detail.collectionId());
        assertEquals(3, detail.totalSteps());

        List<ApiRunSetStep> steps = detail.steps();
        assertEquals(3, steps.size());
        assertEquals(List.of(1, 2, 3), steps.stream().map(ApiRunSetStep::seqNo).toList());
        assertEquals("登录", steps.get(0).name());
        assertEquals("POST", steps.get(0).method());
        assertEquals(3, steps.get(0).status());
        assertEquals(800L, steps.get(0).durationMs());
        assertEquals("connect refused", steps.get(0).errorMsg());
        assertEquals(1, steps.get(1).status());
        assertEquals(120L, steps.get(1).durationMs());
        assertEquals("查单", steps.get(2).name());
        assertEquals("GET", steps.get(2).method());
        assertEquals(0, steps.get(2).status(), "untouched child stays pending 0");
        assertEquals(login.id(), steps.get(0).requestId());
    }

    @Test
    void detail_集合不存在404_人话原文() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> apiRunSetService.detail(999999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode().intValue(),
                "missing ledger must surface NOT_FOUND");
    }

    @Test
    void detail_子行带protocol与断言数_供展示面诚实归因() {
        long projectId = createDetailProject("DT3");
        ApiCollectionInfo collection = createCollection(projectId, "混合传输");
        ApiRequestInfo httpReq = createRequest(collection.id(), "查单", "GET", "/orders/1");
        ApiRequestInfo wsReq = createRequest(collection.id(), "行情推送", "GET", "/quote");
        jdbcTemplate.update("UPDATE tp_apitest_request SET protocol = 'ws' WHERE id = ?", wsReq.id());

        ApiRunSetInfo created = apiRunSetService.create(1L,
                new RunSetCreateInput(collection.id(), null, "归因一轮", "CONTINUE_AND_SUMMARY"));

        // http row passes with 2 assertions landed; ws row passes with zero
        // assertions (connection-reachable only) — the read model must carry
        // protocol + assertion count so the panel never claims "assertions
        // passed" for a step that never had any (W1c honesty red line).
        jdbcTemplate.update("UPDATE tp_apitest_run SET status = 1, duration_ms = 90, "
                        + "assert_detail = '[{\"passed\":true},{\"passed\":true}]'::jsonb "
                        + "WHERE request_id = ? AND run_set_id = ?", httpReq.id(), created.id());
        jdbcTemplate.update("UPDATE tp_apitest_run SET status = 1, duration_ms = 30 "
                + "WHERE request_id = ? AND run_set_id = ?", wsReq.id(), created.id());

        List<ApiRunSetStep> steps = apiRunSetService.detail(created.id()).steps();
        assertEquals(2, steps.size());
        ApiRunSetStep http = steps.get(0);
        ApiRunSetStep ws = steps.get(1);
        assertEquals("http", http.protocol());
        assertEquals(2, http.assertCount(), "landed assert_detail array length is the truth");
        assertEquals("ws", ws.protocol());
        assertEquals(0, ws.assertCount(), "default '[]' row stays zero — display must say connected-no-assertions");
    }

    @Test
    void detail_无子行时steps为空集不谎报() {
        long projectId = createDetailProject("DT2");
        ApiCollectionInfo collection = createCollection(projectId, "空历史");
        ApiRequestInfo req = createRequest(collection.id(), "健康检查", "GET", "/health");
        ApiRunSetInfo created = apiRunSetService.create(1L,
                new RunSetCreateInput(collection.id(), null, null, "STOP_ON_ASSERT_FAIL"));

        // Orphan the children directly (cleanup fixture posture): the ledger
        // detail must honestly report an empty step list, never a null crash.
        jdbcTemplate.update("DELETE FROM tp_apitest_run WHERE run_set_id = ?", created.id());

        ApiRunSetDetail detail = apiRunSetService.detail(created.id());
        assertTrue(detail.steps().isEmpty());
    }
}
