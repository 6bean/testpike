package com.sixbean.testpike.core.apitest.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetPageView;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collection-run ledger service tests ; real dev DB, strict testing
 * standard — no mocks).
 * <p>Covers: open a run over a 3-request collection → ledger row status 0 with the
 * pinned snapshot in tree order + child run rows pending with run_set_id/seq_no;
 * pool snapshot taken at launch; bad interruption mode 400; foreign environment
 * 400 (shared SSRF whitelist guard); empty collection 400; 404 on missing set.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiRunSetServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void sweepRunSetProjectFixtures() {
        ProjectFixtureRig.sweep(jdbcTemplate, projectIds);
    }

    private long createRunSetProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                projectIds, "项目" + code, code);
    }

    private ApiCollectionInfo createCollection(long projectId, String name) {
        return apiCollectionService.create(1L, new CollectionSaveInput(projectId, 0L, name, null));
    }

    /** order lookup consuming the token produced by login — the classic chain shape. */
    private ApiRequestInfo createRequest(long collectionId, String name, String path, String assertions) {
        return apiRequestService.create(1L, new RequestSaveInput(collectionId, name, "GET",
                path, null, null, null, assertions));
    }

    private long createEnv(long projectId, String baseUrl) {
        return jdbcTemplate.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url) "
                + "VALUES (?, ?, ?) RETURNING id", Long.class, projectId, "env-" + baseUrl, baseUrl);
    }

    @Test
    void test_create_pins_definitions_in_tree_order_and_inserts_pending_children() throws Exception {
        long pid = createRunSetProject("RS1");
        ApiCollectionInfo col = createCollection(pid, "订单域");
        ApiRequestInfo login = createRequest(col.id(), "登录", "/api/login",
                "[{\"type\":\"extract\",\"saveAs\":\"token\",\"path\":\"$.data.token\"}]");
        ApiRequestInfo order = createRequest(col.id(), "查订单", "/api/order/{{VAR:token}}",
                "[{\"type\":\"status\",\"expect\":200}]");
        ApiRequestInfo health = createRequest(col.id(), "健康", "/api/health", "[]");
        long envId = createEnv(pid, "https://rs1.test");

        ApiRunSetInfo set = apiRunSetService.create(1L,
                new RunSetCreateInput(col.id(), envId, "冒烟一轮", "CHAIN_DEP_ONLY"));
        assertNotNull(set.id());
        assertEquals(0, set.status(), "读回=事务内账本原形（首跳派发挂提交后钩子）");
        assertEquals(1, apiRunSetService.get(set.id()).status(),
                "提交后首跳已派发=在跑 1（T369 切片3 编排接线）");
        assertEquals(col.id(), set.collectionId());
        assertEquals(pid, set.projectId());
        assertEquals(envId, set.envId());
        assertEquals("CHAIN_DEP_ONLY", set.interruptionMode());
        assertEquals(3, set.totalSteps());

        JsonNode pinned = MAPPER.readTree(set.pinnedJson());
        assertEquals(3, pinned.size(), "快照=三请求全冻结");
        assertEquals(login.id(), pinned.get(0).path("requestId").asLong(), "树序=id 序");
        assertEquals(order.id(), pinned.get(1).path("requestId").asLong());
        assertEquals(health.id(), pinned.get(2).path("requestId").asLong());
        assertEquals("/api/order/{{VAR:token}}", pinned.get(1).path("urlPath").asText(),
                "定义冻结=占位符原文（执行期才解析，活池语义）");

        List<Long> childIds = apiRunSetService.childRunIds(set.id());
        assertEquals(3, childIds.size());
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT request_id, seq_no, status, run_set_id FROM tp_apitest_run "
                        + "WHERE run_set_id = ? ORDER BY seq_no", set.id());
        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).get("seq_no"));
        assertEquals(login.id(), rows.get(0).get("request_id"));
        assertEquals(0, rows.get(0).get("status"), "子行=待执行 0");

        // history + detail read-back (paged door: window list + truthful total)
        assertEquals(1, apiRunSetService.listByCollection(col.id(), 1, 20).list().size());
        assertThrows(ServiceException.class, () -> apiRunSetService.get(999999999L));
    }

    @Test
    void test_listByCollection_pages_newest_first_with_truthful_total() {
        long pid = createRunSetProject("RSP1");
        ApiCollectionInfo col = createCollection(pid, "分页域");
        createRequest(col.id(), "静态", "/api/a", "[]");
        long envId = createEnv(pid, "https://rsp1.test");
        for (int i = 0; i < 3; i++) {
            apiRunSetService.create(1L, new RunSetCreateInput(col.id(), envId, "轮" + i, null));
        }

        // Window 1 of size 2 carries the two newest rows while total spans the collection.
        ApiRunSetPageView first = apiRunSetService.listByCollection(col.id(), 1, 2);
        assertEquals(2, first.list().size());
        assertEquals(3, first.total(), "total counts every ledger of the collection, not the window");
        assertEquals(1, first.page());
        assertEquals(2, first.pageSize());
        ApiRunSetPageView second = apiRunSetService.listByCollection(col.id(), 2, 2);
        assertEquals(1, second.list().size());
        // newest-first: windows partition the ledger without overlap
        assertTrue(first.list().stream().noneMatch(a -> second.list().stream()
                .anyMatch(b -> b.id() == a.id())), "windows never overlap under newest-first order");

        // Clamp dialect echoes back instead of refusing (shared PageWindow).
        ApiRunSetPageView clamped = apiRunSetService.listByCollection(col.id(), 0, 5000);
        assertEquals(1, clamped.page());
        assertEquals(500, clamped.pageSize());

        // Ghost collection refuses through the collection service's single point.
        assertThrows(ServiceException.class,
                () -> apiRunSetService.listByCollection(999999999L, 1, 20));
    }

    @Test
    void test_create_snapshot_pools_initial_vars_for_bookkeeping() {
        long pid = createRunSetProject("RS2");
        ApiCollectionInfo col = createCollection(pid, "订单域");
        createRequest(col.id(), "静态", "/api/a", "[]");
        jdbcTemplate.update("UPDATE tp_apitest_collection SET vars_json = '{\"seed\":\"v1\"}'::jsonb WHERE id = ?",
                col.id());

        ApiRunSetInfo set = apiRunSetService.create(1L,
                new RunSetCreateInput(col.id(), null, "", null));
        assertEquals("STOP_ON_ASSERT_FAIL", set.interruptionMode(), "缺省=停止档（最保守）");
        assertTrue(set.initialVarsJson().contains("seed"), "开池快照=发起瞬间集合池原文（记账面）");
        assertNullValue(set.envId());
    }

    private static void assertNullValue(Object value) {
        assertEquals(null, value, "envId 可空=先建后选旧形一致");
    }

    @Test
    void test_create_rejects_bad_mode_foreign_env_and_empty_collection() {
        long pid = createRunSetProject("RS3");
        ApiCollectionInfo col = createCollection(pid, "订单域");
        createRequest(col.id(), "静态", "/api/a", "[]");

        // any value outside the three form enums → 400 in plain words (no free expressions)
        ServiceException badMode = assertThrows(ServiceException.class, () ->
                apiRunSetService.create(1L, new RunSetCreateInput(col.id(), null, "", "ALWAYS_STOP")));
        assertTrue(badMode.getMessage().contains("中断模式"));

        // foreign-project environment → 400 (same guard as single-request runs, one source)
        long otherPid = createRunSetProject("RS3B");
        long otherEnv = createEnv(otherPid, "https://other.test");
        ServiceException foreign = assertThrows(ServiceException.class, () ->
                apiRunSetService.create(1L, new RunSetCreateInput(col.id(), otherEnv, "", null)));
        assertTrue(foreign.getMessage().contains("项目"));

        // empty collection → 400 (a zero-hop ledger is not a run)
        ApiCollectionInfo empty = createCollection(pid, "空集合");
        ServiceException noReq = assertThrows(ServiceException.class, () ->
                apiRunSetService.create(1L, new RunSetCreateInput(empty.id(), null, "", null)));
        assertTrue(noReq.getMessage().contains("没有请求"));

        // missing collection → 404
        assertThrows(ServiceException.class, () ->
                apiRunSetService.create(1L, new RunSetCreateInput(999999999L, null, "", null)));
    }
}
