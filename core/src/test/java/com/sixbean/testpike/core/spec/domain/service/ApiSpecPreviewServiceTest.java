package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecPreviewInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecPreviewApplyResult;
import com.sixbean.testpike.core.spec.domain.component.ApiSpecDraftItem;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class ApiSpecPreviewServiceTest {

    private static final String SPEC_JSON = """
            {
              "openapi": "3.0.1",
              "info": {"title": "订单服务", "version": "1.0.0"},
              "paths": {
                "/orders": {
                  "get": {"tags": ["订单"], "summary": "查询订单列表",
                          "responses": {"200": {"description": "ok",
                              "content": {"application/json": {"schema": {"type": "object"}}}}}},
                  "post": {"tags": ["订单"], "summary": "创建订单",
                           "requestBody": {"content": {"application/json": {}}},
                           "responses": {"201": {"description": "created",
                               "content": {"application/json": {"schema": {"type": "object"}}}},
                                        "400": {"description": "bad",
                              "content": {"application/json": {"schema": {"type": "object"}}}}}}
                }
              }
            }
            """;

    @Autowired
    private ApiSpecService apiSpecService;
    @Autowired
    private ApiSpecPreviewService apiSpecPreviewService;
    @Autowired
    private com.sixbean.testpike.core.document.domain.service.DocumentService documentService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    /** Projects created by this suite; swept by id tree, never by table wipe. */
    private final List<Long> fixtureProjects = new java.util.ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // R37 teardown: this suite owns its fixture project, so it sweeps that
        // project's row tree instead of truncating shared business tables.
        // A blanket TRUNCATE tp_project CASCADE deleted every document,
        // requirement point and case the shared dev database carried.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjects);
        fixtureProjects.clear();
        code = UUID.randomUUID().toString().substring(0, 8);
        projectId = projectService.create(1L,
                new ProjectCreateInput("预览项目" + code, code, "zh-CN", "P1", null));
        fixtureProjects.add(projectId);
    }

    private String code;

    private long uploadSpec() {
        ApiSpecInfo spec = apiSpecService.upload(1L, projectId, "订单契约",
                SPEC_JSON.getBytes(StandardCharsets.UTF_8), null);
        return spec.id();
    }

    @Test
    void generatePreviewPersistsPendingWithDraftItems() {
        long specId = uploadSpec();
        ApiSpecPreviewInfo preview = apiSpecPreviewService.generate(1L, specId);
        assertNotNull(preview.id());
        assertEquals("pending", preview.status());
        assertEquals(2, preview.items().size());
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_api_spec_preview WHERE id = ?", String.class, preview.id());
        assertEquals("pending", status);
        ApiSpecDraftItem post = preview.items().stream()
                .filter(i -> "POST".equals(i.method())).findFirst().orElseThrow();
        assertTrue(post.assertionsJson().contains("\"duration\""), "默认断言含耗时阈值行");
        assertTrue(post.assertionsJson().contains("$"), "默认断言含公司约定 JSONPath 行");
        assertTrue(post.negativeAssertionsJson().contains("400"), "4xx 定义在才给负例候选");
        assertEquals(false, post.negativeSelected());
    }

    @Test
    void regeneratePreviewIsIdempotentSingleRow() {
        long specId = uploadSpec();
        ApiSpecPreviewInfo first = apiSpecPreviewService.generate(1L, specId);
        ApiSpecPreviewInfo second = apiSpecPreviewService.generate(1L, specId);
        assertEquals(first.id(), second.id(), "同契约重复生成=同行刷新不建新");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec_preview WHERE spec_id = ?",
                Integer.class, specId);
        assertEquals(1, rows);
    }

    @Test
    void applyCreatesCollectionsAndRequestsThenReplaySkips() {
        long specId = uploadSpec();
        ApiSpecPreviewInfo preview = apiSpecPreviewService.generate(1L, specId);
        List<String> allKeys = preview.items().stream().map(ApiSpecDraftItem::endpointKey).toList();

        SpecPreviewApplyResult applied = apiSpecPreviewService.apply(1L, preview.id(), allKeys);
        assertEquals(2, applied.createdRequests());
        assertEquals(0, applied.skipped());

        // Request rows carry no project_id; scope through the owning collection so
        // foreign apitest rows from sibling suites cannot inflate the count (R37).
        Integer requests = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_apitest_request r"
                        + " JOIN tp_apitest_collection c ON c.id = r.collection_id"
                        + " WHERE c.project_id = ?",
                Integer.class, projectId);
        assertEquals(2, requests);
        Integer rootCollections = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_apitest_collection WHERE parent_id = 0 AND project_id = ?",
                Integer.class, projectId);
        assertTrue(rootCollections >= 1, "tags 建集合层级（根至少 1）");

        SpecPreviewApplyResult replay = apiSpecPreviewService.apply(1L, preview.id(), allKeys);
        assertEquals(0, replay.createdRequests(), "重放同清单幂等跳过");
        assertEquals(2, replay.skipped());
        Integer still = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_apitest_request r"
                        + " JOIN tp_apitest_collection c ON c.id = r.collection_id"
                        + " WHERE c.project_id = ?",
                Integer.class, projectId);
        assertEquals(2, still, "幂等重放不双建请求");
    }

    @Test
    void applySelectedSubsetLeavesRestWritable() {
        long specId = uploadSpec();
        ApiSpecPreviewInfo preview = apiSpecPreviewService.generate(1L, specId);
        String one = preview.items().get(0).endpointKey();

        apiSpecPreviewService.apply(1L, preview.id(), List.of(one));
        SpecPreviewApplyResult rest = apiSpecPreviewService.apply(
                1L, preview.id(), List.of(preview.items().get(1).endpointKey()));
        assertEquals(1, rest.createdRequests(), "未选键后可补写");
        // Same project-scoped count as the replay case: collection join owns the scope.
        Integer total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_apitest_request r"
                        + " JOIN tp_apitest_collection c ON c.id = r.collection_id"
                        + " WHERE c.project_id = ?",
                Integer.class, projectId);
        assertEquals(2, total);
    }

    @Test
    void missingPreviewAndForeignSelectionFailFast() {
        assertThrows(ServiceException.class, () -> apiSpecPreviewService.get(999999L));
        long specId = uploadSpec();
        ApiSpecPreviewInfo preview = apiSpecPreviewService.generate(1L, specId);
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecPreviewService.apply(1L, preview.id(), List.of("GET /nope")));
        assertTrue(e.getMessage().contains("预览"), "清单外键人话拒");
    }


    private long uploadDesignDoc() {
        String md = """
                # 订单服务接口设计

                ## 查询订单列表
                GET /orders 查询订单列表，返回分页对象，含 $.code 统一响应。

                版本标记 %s。
                """.formatted(code);
        return documentService.upload(1L, projectId, "订单设计" + code + ".md",
                md.getBytes(StandardCharsets.UTF_8), null).id();
    }

    private ApiSpecDraftItem draftItem(String method, String path, String anchor) {
        return new ApiSpecDraftItem(null, method, path, path, null, List.of("订单"),
                "[]", null, null, "[]", "[]", false, List.of(), null,
                "B", null, anchor);
    }

    @Test
    void channelBWritebackPersistsWithGroundingAndNullSpec() {
        long docId = uploadDesignDoc();
        ApiSpecPreviewInfo preview = apiSpecPreviewService.generateFromDrafts(1L, docId,
                List.of(draftItem("GET", "/orders", "查询订单列表"),
                        draftItem("POST", "/orders", "文档外的发明接口")));
        assertNotNull(preview.id());
        assertEquals("pending", preview.status());
        assertEquals(2, preview.items().size());
        ApiSpecDraftItem hit = preview.items().stream()
                .filter(i -> "GET".equals(i.method())).findFirst().orElseThrow();
        assertEquals("high", hit.confidence(), "锚命中分块正文=接地 high");
        ApiSpecDraftItem miss = preview.items().stream()
                .filter(i -> "POST".equals(i.method())).findFirst().orElseThrow();
        assertEquals("low", miss.confidence(), "无源条目=low 存疑");
        assertNotNull(hit.endpointKey(), "缺键回退补齐=可勾选写入");
        assertEquals("GET /orders", hit.endpointKey());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT spec_id FROM tp_api_spec_preview WHERE id = ?", Long.class, preview.id()),
                "通道 B 行无契约版本行 spec_id=NULL");
        assertEquals(docId, jdbcTemplate.queryForObject(
                "SELECT source_document_id FROM tp_api_spec_preview WHERE id = ?",
                Long.class, preview.id()));
    }

    @Test
    void channelBWritebackSameDocIsIdempotentSingleRow() {
        long docId = uploadDesignDoc();
        ApiSpecPreviewInfo first = apiSpecPreviewService.generateFromDrafts(1L, docId,
                List.of(draftItem("GET", "/orders", "查询订单列表")));
        ApiSpecPreviewInfo second = apiSpecPreviewService.generateFromDrafts(1L, docId,
                List.of(draftItem("GET", "/orders", "查询订单列表"),
                        draftItem("PUT", "/orders/1", "更新订单")));
        assertEquals(first.id(), second.id(), "同文档重复回写=同行刷新不建双行");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_api_spec_preview WHERE source_document_id = ?",
                Integer.class, docId);
        assertEquals(1, rows);
        assertEquals(2, second.items().size(), "刷新后条目=最新清单");
    }

    @Test
    void channelBApplyUsesDocumentNameAsRootFallback() {
        long docId = uploadDesignDoc();
        ApiSpecPreviewInfo preview = apiSpecPreviewService.generateFromDrafts(1L, docId,
                List.of(draftItem("GET", "/orders", "查询订单列表")));
        SpecPreviewApplyResult applied = apiSpecPreviewService.apply(
                1L, preview.id(), List.of(preview.items().get(0).endpointKey()));
        assertEquals(1, applied.createdRequests());
        Integer roots = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_apitest_collection WHERE parent_id = 0", Integer.class);
        assertTrue(roots >= 1, "通道 B 勾选写入建集合层级");
    }

    @Test
    void channelBWritebackMissingDocumentFailsFast() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecPreviewService.generateFromDrafts(1L, 987654L,
                        List.of(draftItem("GET", "/x", "y"))));
        assertTrue(e.getMessage().contains("文档"), "文档不存在人话拒");
    }


    @Test
    void latestByDocumentReturnsChannelBRow() {
        long docId = uploadDesignDoc();
        ApiSpecPreviewInfo written = apiSpecPreviewService.generateFromDrafts(1L, docId,
                List.of(draftItem("GET", "/orders", "查询订单列表")));
        ApiSpecPreviewInfo polled = apiSpecPreviewService.latestByDocument(docId);
        assertEquals(written.id(), polled.id(), "按文档反查=回写同行");
        assertEquals(1, polled.items().size());
        assertEquals("high", polled.items().get(0).confidence(), "反查行保留接地判置信");
    }

    @Test
    void latestByDocumentMissingWritebackOrDocFailsFastWithPlainMessage() {
        ServiceException never = assertThrows(ServiceException.class,
                () -> apiSpecPreviewService.latestByDocument(123456L));
        assertTrue(never.getMessage().contains("抽取"), "未回写人话含「抽取」");
        long docId = uploadDesignDoc();
        ServiceException foreign = assertThrows(ServiceException.class,
                () -> apiSpecPreviewService.latestByDocument(docId + 999999L));
        assertTrue(foreign.getMessage().contains("文档") || foreign.getMessage().contains("抽取"),
                "文档不存在走同一人话口径");
    }

    @Test
    void latestByDocumentIgnoresChannelARows() {
        long specId = uploadSpec();
        apiSpecPreviewService.generate(1L, specId);
        ServiceException e = assertThrows(ServiceException.class,
                () -> apiSpecPreviewService.latestByDocument(777L));
        assertTrue(e.getMessage().contains("抽取"), "通道 A 行不参与按文档反查");
    }
}
