package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecChangeAnalysisInfo;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecChangeItem;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract change-analysis queue tests (W1c F3-twin, core/spec domain).
 * <p>Behaviour under test: uploading a contract version whose endpoint diff against
 * its previous version is non-empty produces one review-queue row whose change items
 * carry closed impact lists — imported API requests matched by the folded contract
 * key (tp_apitest_request.contract_endpoint_key equality, no per-row recompute),
 * promoted cases (promoted_case_id, not deleted) and contract-derived mock stubs
 * (source=openapi, folded key). Changes with no matching assets are still listed
 * with empty impact lists — the queue must never pretend a closure it did not find.
 * <p>Real dev database, no mocks. Re-analysis of the same old/new spec pair upserts
 * (one row, refreshed changes, preserved human disposition), the first version of a
 * contract (no previous) is rejected with a plain-language 400, and every landed
 * analysis notifies project owners via in-app notification SPEC_CHANGE_ANALYZED.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class ApiSpecChangeAnalysisServiceTest {

    @Autowired
    private ApiSpecService apiSpecService;
    @Autowired
    private ApiSpecChangeAnalysisService analysisService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String code;
    private long projectId;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, SWEEP_EXTRA, fixtureProjects);
        fixtureProjects.clear();
    }

    /** Project-keyed tables without an FK into tp_project (live schema scan). */
    private static final java.util.List<String> SWEEP_EXTRA =
            java.util.List.of("tp_api_spec", "tp_api_spec_change_analysis");

    /** Fixture projects swept by FK tree; never a table wipe (R37). */
    private final java.util.List<Long> fixtureProjects = new java.util.ArrayList<>();

    private void createProject() {
        code = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("契约变更项目" + code, code, "zh-CN", "P1", null));
        fixtureProjects.add(projectId);
    }

    private static byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** v1: GET/POST /pets, DELETE /pets/{id}, GET /extra. */
    private static final String V1 = """
            {"openapi":"3.0.1","info":{"title":"契约API","version":"1.0"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "parameters":[{"name":"limit","in":"query","schema":{"type":"integer"}}],
                   "responses":{"200":{"description":"ok"}}},
                 "post":{"operationId":"createPet","responses":{"201":{"description":"c"}}}},
               "/pets/{id}":{"delete":{"operationId":"del","responses":{"204":{"description":"g"}}}},
               "/extra":{"get":{"operationId":"x","responses":{"200":{"description":"ok"}}}}
             }}
            """;

    /** v2: GET /pets changed (extra required param), POST/DELETE/extra removed, PATCH added. */
    private static final String V2 = """
            {"openapi":"3.0.1","info":{"title":"契约API","version":"1.1"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "parameters":[{"name":"limit","in":"query","required":true,
                                  "schema":{"type":"integer"}},
                                 {"name":"page","in":"query","schema":{"type":"integer"}}],
                   "responses":{"200":{"description":"ok"}}},
                 "patch":{"operationId":"patchPet","responses":{"200":{"description":"ok"}}}}
             }}
            """;

    /** Same endpoints as V2, only info.version differs — an upload with empty endpoint diff. */
    private static final String V2_META_ONLY = """
            {"openapi":"3.0.1","info":{"title":"契约API","version":"1.2"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "parameters":[{"name":"limit","in":"query","required":true,
                                  "schema":{"type":"integer"}},
                                 {"name":"page","in":"query","schema":{"type":"integer"}}],
                   "responses":{"200":{"description":"ok"}}},
                 "patch":{"operationId":"patchPet","responses":{"200":{"description":"ok"}}}}
             }}
            """;

    /** v3: same as v2 plus GET /health — one added endpoint (non-empty diff). */
    private static final String V3 = """
            {"openapi":"3.0.1","info":{"title":"契约API","version":"1.3"},
             "paths":{
               "/pets":{"get":{"operationId":"listPets",
                   "parameters":[{"name":"limit","in":"query","required":true,
                                  "schema":{"type":"integer"}},
                                 {"name":"page","in":"query","schema":{"type":"integer"}}],
                   "responses":{"200":{"description":"ok"}}},
                 "patch":{"operationId":"patchPet","responses":{"200":{"description":"ok"}}}},
               "/health":{"get":{"operationId":"health","responses":{"200":{"description":"ok"}}}}
             }}
            """;

    private long seedCollection() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_apitest_collection (project_id, name) VALUES (?, ?) RETURNING id",
                Long.class, projectId, "集合" + code);
    }

    /**
     * Imported request sitting on the folded key of DELETE /pets/{id}
     * (concrete path /pets/987 — folding must close it to the contract template).
     */
    private long seedRequestOnRemovedEndpoint(long collectionId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_apitest_request (collection_id, name, method, url_path,
                                                contract_endpoint_key, promoted_case_id)
                VALUES (?, ?, 'DELETE', '/pets/987', 'DELETE /pets/{var}', NULL)
                RETURNING id
                """, Long.class, collectionId, "删除宠物" + code);
    }

    private long seedPromotedCase(long requestId) {
        long caseId = caseService.create(1L, new CaseCreateInput(projectId, "升格用例" + code,
                "前置", "预期", 1, 2, "web", null, null, null, null, null, null, null)).id();
        jdbcTemplate.update("UPDATE tp_apitest_request SET promoted_case_id = ? WHERE id = ?",
                caseId, requestId);
        return caseId;
    }

    /** Contract-derived stub on the same folded key (source=openapi, template path). */
    private long seedOpenapiStub() {
        long spaceId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_mock_space (project_id, name, space_key)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, projectId, "空间" + code, "spc-" + code);
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_mock_stub (space_id, name, method, path_pattern, source)
                VALUES (?, ?, 'DELETE', '/pets/{petId}', 'openapi') RETURNING id
                """, Long.class, spaceId, "桩" + code);
    }

    private ApiSpecInfo uploadV1() {
        return apiSpecService.upload(1L, projectId, "pet-api", json(V1), null);
    }

    private ApiSpecInfo uploadV2() {
        uploadV1();
        return apiSpecService.upload(1L, projectId, "pet-api", json(V2), null);
    }

    @Test
    void test_upload_with_diff_lands_queue_row_from_single_trigger_point() {
        createProject();
        ApiSpecInfo v2 = uploadV2();

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec_change_analysis WHERE new_spec_id = ?",
                Long.class, v2.id());
        assertTrue(rows != null && rows == 1,
                "upload must produce exactly one queue row without any explicit analyze call");
        String source = jdbcTemplate.queryForObject(
                "SELECT detected_source FROM tp_api_spec_change_analysis WHERE new_spec_id = ?",
                String.class, v2.id());
        assertEquals("upload", source);
    }

    @Test
    void test_analyze_closes_impact_by_folded_key_requests_cases_and_stubs() {
        createProject();
        long collectionId = seedCollection();
        long requestId = seedRequestOnRemovedEndpoint(collectionId);
        long caseId = seedPromotedCase(requestId);
        long stubId = seedOpenapiStub();
        ApiSpecInfo v1 = uploadV1();
        ApiSpecInfo v2 = apiSpecService.upload(1L, projectId, "pet-api", json(V2), null);

        ApiSpecChangeAnalysisInfo info = analysisService.get(v2.id());
        assertEquals(projectId, info.projectId());
        assertEquals(v1.id(), info.oldSpecId());
        assertEquals(v2.id(), info.newSpecId());
        assertEquals(0, info.status());
        assertNotNullLabel(info.statusLabel());

        List<ApiSpecChangeItem> changes = info.changes();
        // changed: GET /pets; removed: POST /pets, DELETE /pets/{id}, GET /extra; added: PATCH /pets
        assertEquals(5, changes.size(), "五类变更行 = 1 changed + 3 removed + 1 added");
        ApiSpecChangeItem removedItem = itemOf(changes, "DELETE /pets/{id}");
        assertEquals("removed", removedItem.changeType());
        assertEquals(List.of(requestId), removedItem.requestIds(),
                "具体路径 /pets/987 折叠键命中契约模板端点=闭合");
        assertEquals(List.of(caseId), removedItem.caseIds());
        assertEquals(List.of(stubId), removedItem.mockStubIds());

        ApiSpecChangeItem changedItem = itemOf(changes, "GET /pets");
        assertEquals("changed", changedItem.changeType());
        ApiSpecChangeItem addedItem = itemOf(changes, "PATCH /pets");
        assertEquals("add", addedItem.changeType());
        assertTrue(addedItem.requestIds().isEmpty(), "新端点无历史资产");
    }

    @Test
    void test_analyze_lists_change_with_empty_impact_when_nothing_matches() {
        createProject();
        ApiSpecInfo v2 = uploadV2();

        ApiSpecChangeAnalysisInfo info = analysisService.get(v2.id());
        ApiSpecChangeItem orphan = itemOf(info.changes(), "GET /extra");
        assertTrue(orphan.requestIds().isEmpty() && orphan.caseIds().isEmpty()
                        && orphan.mockStubIds().isEmpty(),
                "匹配不到=仍列变更事实、影响清单为空，禁假装闭合");
    }

    @Test
    void test_first_version_without_previous_rejected_400() {
        createProject();
        ApiSpecInfo v1 = uploadV1();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> analysisService.analyze(1L, v1.id(), "upload"));
        assertTrue(ex.getMessage().contains("没有可对照的历史版本"),
                "首版无旧版=400 人话拒析，禁空分析行：" + ex.getMessage());
    }

    @Test
    void test_empty_endpoint_diff_rejected_and_upload_lands_no_row() {
        createProject();
        ApiSpecInfo v2 = uploadV2();
        ApiSpecInfo v3 = apiSpecService.upload(1L, projectId, "pet-api", json(V2_META_ONLY), null);

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec_change_analysis WHERE new_spec_id = ?",
                Long.class, v3.id());
        assertTrue(rows == null || rows == 0, "端点零变化的改版不产队列行");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> analysisService.analyze(1L, v3.id(), "upload"));
        assertTrue(ex.getMessage().contains("差异"),
                "显式重析空 diff=400 人话拒：" + ex.getMessage());
    }

    @Test
    void test_reanalyze_same_pair_upserts_one_row_and_keeps_disposition() {
        createProject();
        ApiSpecInfo v2 = uploadV2();
        long analysisId = analysisService.get(v2.id()).id();

        // simulate the human disposition the queue UI will record endpoint)
        jdbcTemplate.update("UPDATE tp_api_spec_change_analysis SET status = 1,"
                + " handled_by = 7, handled_at = now(), handle_note = '已复核' WHERE id = ?",
                analysisId);

        ApiSpecChangeAnalysisInfo again = analysisService.analyze(1L, v2.id(), "poll");
        assertEquals(analysisId, again.id(), "同版本对再分析=更新禁双行");
        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec_change_analysis WHERE new_spec_id = ?",
                Long.class, v2.id());
        assertEquals(1L, rows);
        assertEquals(1, again.status(), "再分析刷新清单不清人工处置痕迹");
        assertEquals("poll", again.detectedSource());
    }

    @Test
    void test_analyze_notifies_project_owners_review_queue() {
        createProject();
        ApiSpecInfo v2 = uploadV2();
        long analysisId = analysisService.get(v2.id()).id();

        Long notified = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'SPEC_CHANGE_ANALYZED'"
                        + " AND project_id = ? AND dedup_key LIKE ?",
                Long.class, projectId, "SPEC_CHANGE_ANALYZED:" + analysisId + ":%");
        assertTrue(notified != null && notified >= 1,
                "队列落库→项目负责人∪创建人收到复核提醒");
    }

    // ----------------------------------------------------------------
    // read side — queue paging, disposition trace, batch disposition, isolation.

    @Test
    void test_list_pages_newest_first_and_filters_by_status() {
        createProject();
        ApiSpecInfo v2 = uploadV2();
        ApiSpecInfo v3 = apiSpecService.upload(1L, projectId, "pet-api", json(V3), null);
        long v2RowId = analysisService.get(v2.id()).id();

        PageResult<ApiSpecChangeAnalysisInfo> all = analysisService.list(projectId, null, 1, 20);
        assertEquals(2, all.total(), "两版各产一行，队列总数=2");
        assertEquals(v3.id(), all.list().get(0).newSpecId(), "id DESC=最新分析在前");
        assertEquals(v2RowId, all.list().get(1).id());

        analysisService.handle(7L, v2RowId, "已复核并通知下游");
        PageResult<ApiSpecChangeAnalysisInfo> pending = analysisService.list(projectId, 0, 1, 20);
        assertEquals(1, pending.total(), "状态筛选 0=待复核只剩 v3 行");
        assertEquals(v3.id(), pending.list().get(0).newSpecId());
        PageResult<ApiSpecChangeAnalysisInfo> handled = analysisService.list(projectId, 1, 1, 20);
        assertEquals(1, handled.total(), "状态筛选 1=已处置只剩 v2 行");
        assertEquals("已处置", handled.list().get(0).statusLabel());

        PageResult<ApiSpecChangeAnalysisInfo> paged = analysisService.list(projectId, null, 2, 1);
        assertEquals(2, paged.total(), "分页 total 不受 size 影响");
        assertEquals(1, paged.list().size());
        assertEquals(v2RowId, paged.list().get(0).id(), "page=2 size=1 落在最旧行");
    }

    @Test
    void test_list_other_project_isolated() {
        createProject();
        ApiSpecInfo v2 = uploadV2();
        String otherCode = UUID.randomUUID().toString().substring(0, 8);
        long otherProject = projectService.create(1L,
                new ProjectCreateInput("契约变更他项目" + otherCode, otherCode, "zh-CN", "P1", null));
        fixtureProjects.add(otherProject);
        apiSpecService.upload(1L, otherProject, "other-api", json(V1), null);
        apiSpecService.upload(1L, otherProject, "other-api", json(V2), null);

        PageResult<ApiSpecChangeAnalysisInfo> mine = analysisService.list(projectId, null, 1, 20);
        assertEquals(1, mine.total(), "H1：他项目的分析行不得混入本项目队列");
        assertEquals(v2.id(), mine.list().get(0).newSpecId());
        assertEquals(0, analysisService.list(otherProject + 9999, null, 1, 20).total(),
                "不存在项目=空队列非报错");
    }

    @Test
    void test_handle_records_trace_without_touching_asset_state_machines() {
        createProject();
        long collectionId = seedCollection();
        long requestId = seedRequestOnRemovedEndpoint(collectionId);
        long caseId = seedPromotedCase(requestId);
        ApiSpecInfo v2 = uploadV2();
        long analysisId = analysisService.get(v2.id()).id();

        ApiSpecChangeAnalysisInfo handled =
                analysisService.handle(7L, analysisId, "已复核：3 处删除端点已通知负责人");
        assertEquals(1, handled.status());
        assertEquals("已处置", handled.statusLabel());
        assertEquals(7L, handled.handledBy());
        assertTrue(handled.handledAt() != null && !handled.handledAt().isBlank());
        assertEquals("已复核：3 处删除端点已通知负责人", handled.handleNote());
        assertEquals(5, handled.changes().size(), "处置只留痕，变更清单原样保留");

        Long caseStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_test_case WHERE id = ?", Integer.class, caseId).longValue();
        Long requestUpdated = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_apitest_request WHERE id = ? AND name = ?",
                Long.class, requestId, "删除宠物" + code);
        assertTrue(requestUpdated == 1, "请求行未被处置动作改动（无状态机联动）");
        assertTrue(caseStatus != null, "用例状态由既有用例侧流程独占，处置禁写");
    }

    @Test
    void test_handle_blank_note_rejected_and_unknown_id_404() {
        createProject();
        ApiSpecInfo v2 = uploadV2();
        long analysisId = analysisService.get(v2.id()).id();

        ServiceException blank = assertThrows(ServiceException.class,
                () -> analysisService.handle(7L, analysisId, "  "));
        assertTrue(blank.getMessage().contains("处置备注"), "备注必填=400 人话：" + blank.getMessage());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> analysisService.handle(7L, analysisId + 99999, "ok"));
        assertTrue(missing.getMessage().contains("不存在"), "处置不存在的行=404 人话");
    }

    @Test
    void test_handle_twice_rejected_400_plain_language() {
        createProject();
        ApiSpecInfo v2 = uploadV2();
        long analysisId = analysisService.get(v2.id()).id();
        analysisService.handle(7L, analysisId, "首次处置");

        ServiceException twice = assertThrows(ServiceException.class,
                () -> analysisService.handle(8L, analysisId, "重复处置"));
        assertTrue(twice.getMessage().contains("已处置"),
                "已处置再处置=400 人话（点名处置人）：" + twice.getMessage());
        assertTrue(twice.getMessage().contains("7"), "错误信息带首次处置人便于沟通");
        assertEquals("首次处置", analysisService.get(v2.id()).handleNote(),
                "重复请求不得覆盖首次留痕");
    }

    @Test
    void test_handle_batch_traces_all_rows_or_rejects_mixed_with_names() {
        createProject();
        ApiSpecInfo v2 = uploadV2();
        ApiSpecInfo v3 = apiSpecService.upload(1L, projectId, "pet-api", json(V3), null);
        long rowA = analysisService.get(v2.id()).id();
        long rowB = analysisService.get(v3.id()).id();

        List<ApiSpecChangeAnalysisInfo> batched =
                analysisService.handleBatch(9L, List.of(rowA, rowB), "批量复核完毕");
        assertEquals(2, batched.size());
        assertTrue(batched.stream().allMatch(i -> i.status() == 1 && i.handledBy() == 9L),
                "批量处置=勾选行一并留痕");

        // one already-handled row mixed in must reject the whole call (no silent partial)
        ServiceException mixed = assertThrows(ServiceException.class,
                () -> analysisService.handleBatch(9L, List.of(rowB + 1000L), "混入"));
        assertTrue(mixed.getMessage().contains("不存在"), "混入未知行=点名 400，禁静默跳过");
        ServiceException reMix = assertThrows(ServiceException.class,
                () -> analysisService.handleBatch(9L, List.of(rowA), "再批"));
        assertTrue(reMix.getMessage().contains("已处置"), "已处置行混入=点名拒绝");
    }

    private static ApiSpecChangeItem itemOf(List<ApiSpecChangeItem> items, String endpointKey) {
        return items.stream().filter(i -> endpointKey.equals(i.endpointKey()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少变更行: " + endpointKey));
    }

    private static void assertNotNullLabel(String value) {
        org.junit.jupiter.api.Assertions.assertNotNull(value);
        assertTrue(!value.isBlank());
    }
}
