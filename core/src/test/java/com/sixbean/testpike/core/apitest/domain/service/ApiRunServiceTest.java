package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ApiRunServiceTest {

    @Autowired
    private ApiRunService apiRunService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> runTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, runTrackedProjects);
    }

    private long createRunProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                runTrackedProjects, "项目" + code, code);
    }

    
    private ApiRequestInfo seedRequest(long projectId) {
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "订单域", null));
        return apiRequestService.create(1L, new RequestSaveInput(root.id(), "查订单", "GET",
                "/api/v1/orders", null, null, null, "[{\"type\":\"status\",\"expect\":200}]"));
    }

    private long createEnv(long projectId, String baseUrl) {
        return jdbcTemplate.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url) "
                + "VALUES (?, ?, ?) RETURNING id", Long.class, projectId, "env-" + baseUrl, baseUrl);
    }

    @Test
    void test_create_run_pending_and_get() {
        long pid = createRunProject("W1R1");
        ApiRequestInfo req = seedRequest(pid);
        long envId = createEnv(pid, "https://order-a.test");

        ApiRunInfo run = apiRunService.create(1L, new RunCreateInput(req.id(), envId));
        assertNotNull(run.id());
        assertEquals(req.id(), run.requestId());
        assertEquals(envId, run.envId());
        assertEquals(0, run.status(), "新建运行=待执行 0（派发后由执行链回写状态）");
        assertEquals("[]", run.assertDetail(), "未跑=断言明细空数组原文");

        ApiRunInfo got = apiRunService.get(run.id());
        assertEquals(run.id(), got.id());
        assertThrows(ServiceException.class, () -> apiRunService.get(999999999L));
    }

    @Test
    void test_create_rejects_missing_request_and_foreign_env() {
        long pid = createRunProject("W1R2");
        ApiRequestInfo req = seedRequest(pid);

        ServiceException nf = assertThrows(ServiceException.class,
                () -> apiRunService.create(1L, new RunCreateInput(999999999L, null)));
        assertTrue(nf.getMessage().contains("请求"));

        long otherPid = createRunProject("W1R2B");
        long otherEnv = createEnv(otherPid, "https://other.test");
        ServiceException mismatch = assertThrows(ServiceException.class,
                () -> apiRunService.create(1L, new RunCreateInput(req.id(), otherEnv)));
        assertTrue(mismatch.getMessage().contains("项目"));

        ApiRunInfo noEnv = apiRunService.create(1L, new RunCreateInput(req.id(), null));
        assertNull(noEnv.envId());
    }

    @Test
    void test_result_write_endpoints_assert_detail_validation_and_ordering() throws Exception {
        long pid = createRunProject("W1R3");
        ApiRequestInfo req = seedRequest(pid);
        long envId = createEnv(pid, "https://order-b.test");
        ApiRunInfo first = apiRunService.create(1L, new RunCreateInput(req.id(), envId));
        ApiRunInfo second = apiRunService.create(1L, new RunCreateInput(req.id(), envId));

        assertThrows(ServiceException.class, () ->
                apiRunService.recordResult(first.id(), 1, 120L, "{not json", "", ""));
        assertThrows(ServiceException.class, () ->
                apiRunService.recordResult(first.id(), 1, 120L, "{\"a\":1}", "", ""));

        String detail = "[{\"type\":\"status\",\"expect\":200,\"passed\":true,\"actual\":200}]";
        apiRunService.recordResult(first.id(), 1, 120L, detail, "s3://apitest/resp-1.gz", "");
        ApiRunInfo done = apiRunService.get(first.id());
        assertEquals(1, done.status());
        assertEquals(120L, done.durationMs());
        assertEquals(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(detail),
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(done.assertDetail()),
                "断言明细数组结构完整回读（对象/数组语义等值）");
        assertEquals("s3://apitest/resp-1.gz", done.artifactRef());

        apiRunService.recordResult(second.id(), 3, null, "[]", "", "连接被拒绝");
        ApiRunInfo errored = apiRunService.get(second.id());
        assertEquals(3, errored.status());
        assertEquals("连接被拒绝", errored.errorMsg());

        assertThrows(ServiceException.class, () ->
                apiRunService.recordResult(second.id(), 7, null, "[]", "", ""));

        List<ApiRunInfo> runs = apiRunService.listByRequest(req.id());
        assertEquals(2, runs.size());
        assertEquals(second.id(), runs.get(0).id(), "最新运行在前");
    }

    @Test
    void test_delete_single_and_clear_keeps_promoted_trace() {
        long pid = createRunProject("W1R4");
        ApiRequestInfo req = seedRequest(pid);
        ApiRunInfo run = apiRunService.create(1L, new RunCreateInput(req.id(), null));

        assertThrows(ServiceException.class, () -> apiRunService.delete(1L, 999999999L));

        apiRunService.clearUnpromoted(req.id());
        assertTrue(apiRunService.listByRequest(req.id()).isEmpty());
        apiRequestService.delete(1L, req.id());

        ApiRequestInfo promoted = apiRequestService.create(1L, new RequestSaveInput(
                apiCollectionService.list(pid).get(0).id(), "已升格请求", "POST", "/api/v1/pay",
                null, null, null, null));
        jdbcTemplate.update("UPDATE tp_apitest_request SET promoted_case_id = 555 WHERE id = ?",
                promoted.id());
        ApiRunInfo kept = apiRunService.create(1L, new RunCreateInput(promoted.id(), null));
        apiRunService.clearUnpromoted(promoted.id());
        assertEquals(1, apiRunService.listByRequest(promoted.id()).size(),
                "已资产化请求的运行记录=证据链，清空仅作用未升格");
        apiRunService.delete(1L, kept.id());
        assertTrue(apiRunService.listByRequest(promoted.id()).isEmpty());
    }
}
