package com.sixbean.testpike.core.apitest.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplateInfo;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplatePageView;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplateSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flow-template storage tests ; real dev DB, strict testing
 * standard — no mocks).
 * <p>Covers: save freezes the collection's requests in user order with the same
 * pinned shape the collection-run ledger uses (orchestration rows included);
 * paged list window clamps and counts truthfully; instantiate copies every
 * snapshot row through the request save gate into the target collection
 * (order, assertions, precondition/loop, protocol all carried); the provenance
 * FK is SET NULL — deleting the source collection keeps the frozen snapshot
 * alive; missing template 404; empty source collection refused with a human
 * message.
 */
@SpringBootTest(classes = CoreApplication.class)
class FlowTemplateServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private FlowTemplateService flowTemplateService;

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
    void sweepFlowTplProjectFixtures() {
        ProjectFixtureRig.sweep(jdbcTemplate, projectIds);
    }

    private long createFlowTplProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                projectIds, "项目" + code, code);
    }

    private ApiCollectionInfo createCollection(long projectId, String name) {
        return apiCollectionService.create(1L, new CollectionSaveInput(projectId, 0L, name, null));
    }

    private ApiRequestInfo createRequest(long collectionId, String name, String path, String assertions,
                                         String preconditionJson, String loopJson, String protocol) {
        return apiRequestService.create(1L, new RequestSaveInput(collectionId, name, "GET",
                path, null, null, null, assertions, preconditionJson, loopJson, protocol));
    }

    @Test
    void test_save_freezes_snapshot_in_user_order_with_orchestration_rows() throws Exception {
        long pid = createFlowTplProject("FT1");
        ApiCollectionInfo col = createCollection(pid, "订单域");
        createRequest(col.id(), "登录", "/api/login",
                "[{\"type\":\"extract\",\"saveAs\":\"token\",\"path\":\"$.data.token\"}]", null, null, null);
        createRequest(col.id(), "查订单", "/api/order/{{VAR:token}}",
                "[{\"type\":\"status\",\"expect\":200}]",
                "{\"field\":\"status\",\"op\":\"eq\",\"expect\":200}",
                "{\"mode\":\"count\",\"times\":3,\"delayMs\":100}", null);
        createRequest(col.id(), "健康", "/api/health", "[]", null, null, "ws");

        FlowTemplateInfo saved = flowTemplateService.save(1L,
                new FlowTemplateSaveInput(col.id(), "订单主流程", "演示剧本模板"));
        assertNotNull(saved.id());
        assertEquals(pid, saved.projectId(), "模板归属=源集合项目（服务端派生，禁客户端谎报）");
        assertEquals(col.id(), saved.sourceCollectionId());

        JsonNode snapshot = MAPPER.readTree(saved.stepsSnapshotJson());
        assertEquals(3, snapshot.size(), "快照=全量冻结");
        assertEquals("查订单", snapshot.get(1).path("name").asText(), "序=集合内用户序");
        assertEquals("/api/order/{{VAR:token}}", snapshot.get(1).path("urlPath").asText(),
                "占位符原文冻结（实例化期才进目标集合）");
        assertFalse(snapshot.get(0).has("preconditionJson"), "空编排=不落键（pinned 形状单源）");
        assertTrue(snapshot.get(1).has("preconditionJson"), "前置条件行原样冻结");
        assertTrue(snapshot.get(1).has("loopJson"), "循环行原样冻结");
        assertEquals("ws", snapshot.get(2).path("protocol").asText(), "传输判别随行冻结（ws 请求禁实例化退化 http）");

        // Round-trip read door: get() returns the same frozen snapshot.
        assertEquals(saved.id(), flowTemplateService.get(saved.id()).id());
    }

    @Test
    void test_list_window_clamps_counts_and_isolates_projects() {
        long pidA = createFlowTplProject("FTA");
        long pidB = createFlowTplProject("FTB");
        ApiCollectionInfo colA = createCollection(pidA, "A域");
        ApiCollectionInfo colB = createCollection(pidB, "B域");
        createRequest(colA.id(), "r1", "/a1", "[]", null, null, null);
        createRequest(colB.id(), "r1", "/b1", "[]", null, null, null);
        for (int i = 1; i <= 3; i++) {
            flowTemplateService.save(1L, new FlowTemplateSaveInput(colA.id(), "模板" + i, ""));
        }
        flowTemplateService.save(1L, new FlowTemplateSaveInput(colB.id(), "B模板", ""));

        FlowTemplatePageView page = flowTemplateService.list(pidA, 1, 2);
        assertEquals(2, page.list().size(), "窗口=2 行");
        assertEquals(3, page.total(), "总数诚实=该项目全部模板");
        FlowTemplatePageView clamped = flowTemplateService.list(pidA, 1, 99999);
        assertEquals(500, clamped.pageSize(), "窗口上限钳制回显（禁一次拖全表）");
        assertEquals(3, clamped.list().size());
        assertEquals(1, flowTemplateService.list(pidB, 1, 20).total(), "项目切片隔离");

        assertThrows(ServiceException.class, () -> flowTemplateService.get(999999999L));
    }

    @Test
    void test_instantiate_copies_every_row_through_the_save_gate_in_order() {
        long pid = createFlowTplProject("FT2");
        ApiCollectionInfo source = createCollection(pid, "源集");
        createRequest(source.id(), "登录", "/api/login",
                "[{\"type\":\"extract\",\"saveAs\":\"token\",\"path\":\"$.data.token\"}]", null, null, null);
        createRequest(source.id(), "查订单", "/api/order/{{VAR:token}}",
                "[{\"type\":\"status\",\"expect\":200}]",
                "{\"field\":\"status\",\"op\":\"eq\",\"expect\":200}",
                "{\"mode\":\"until\",\"until\":{\"field\":\"status\",\"op\":\"eq\",\"expect\":204},\"maxTimes\":5}",
                null);
        FlowTemplateInfo tpl = flowTemplateService.save(1L,
                new FlowTemplateSaveInput(source.id(), "两跳流程", null));

        // Cross-project consumption = the batch's headline capability.
        long pid2 = createFlowTplProject("FT3");
        ApiCollectionInfo target = createCollection(pid2, "目标集");
        List<ApiRequestInfo> created = flowTemplateService.instantiate(1L, tpl.id(), target.id());
        assertEquals(2, created.size());

        List<ApiRequestInfo> rows = apiRequestService.list(target.id());
        assertEquals(2, rows.size(), "实例化后目标集合=2 请求（走保存闸全链）");
        assertEquals("登录", rows.get(0).name(), "序=模板冻结序");
        assertEquals("/api/order/{{VAR:token}}", rows.get(1).urlPath());
        assertTrue(rows.get(1).preconditionJson().contains("\"eq\""), "前置条件随行落地");
        assertTrue(rows.get(1).loopJson().contains("until"), "循环行随行落地（保存闸同谱放行）");
        assertNotNull(rows.get(0).assertionsJson());
    }

    @Test
    void test_missing_target_and_empty_source_are_refused_with_human_messages() {
        long pid = createFlowTplProject("FT4");
        ApiCollectionInfo empty = createCollection(pid, "空集");
        assertThrows(ServiceException.class,
                () -> flowTemplateService.save(1L, new FlowTemplateSaveInput(empty.id(), "空模板", null)),
                "空集合冻结=无意义，人话拒（同集合运行先例姿势）");

        ApiCollectionInfo col = createCollection(pid, "有集");
        createRequest(col.id(), "r", "/r", "[]", null, null, null);
        FlowTemplateInfo tpl = flowTemplateService.save(1L, new FlowTemplateSaveInput(col.id(), "T", null));
        assertThrows(ServiceException.class,
                () -> flowTemplateService.instantiate(1L, tpl.id(), 999999999L), "目标集合不存在 404");
    }

    @Test
    void test_source_collection_deletion_keeps_the_frozen_snapshot() {
        long pid = createFlowTplProject("FT5");
        ApiCollectionInfo col = createCollection(pid, "会删的源集");
        createRequest(col.id(), "r1", "/a", "[]", null, null, null);
        FlowTemplateInfo tpl = flowTemplateService.save(1L,
                new FlowTemplateSaveInput(col.id(), "幸存模板", null));

        // Provenance FK = ON DELETE SET NULL: the snapshot is standalone data,
        // the source is traceability only — its deletion must not block and must
        // not destroy the template (design verdict of the recon).
        jdbcTemplate.update("DELETE FROM tp_apitest_request WHERE collection_id = ?", col.id());
        jdbcTemplate.update("DELETE FROM tp_apitest_collection WHERE id = ?", col.id());

        FlowTemplateInfo still = flowTemplateService.get(tpl.id());
        assertNull(still.sourceCollectionId(), "来源已删=判别位空，快照幸存");
        assertEquals(1, MAPPER_nullSafeRead(still.stepsSnapshotJson()).size(), "冻结快照零损失");
    }

    /** readTree wrapper keeping the assertion line readable (checked exception). */
    private static JsonNode MAPPER_nullSafeRead(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
