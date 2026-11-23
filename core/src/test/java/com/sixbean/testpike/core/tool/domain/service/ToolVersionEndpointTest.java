package com.sixbean.testpike.core.tool.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolDownloadFile;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolRollbackInput;
import com.sixbean.testpike.core.tool.api.dto.ToolUpdateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionDetail;
import com.sixbean.testpike.core.tool.domain.component.ToolPackSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ToolVersionEndpointTest {

    @Autowired
    private ToolService toolService;

    @Autowired
    private ToolPackSupport toolPackSupport;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ToolSuiteSupport fixture;

    @BeforeEach
    void openVersionSuite() {
        fixture = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of());
    }

    @AfterEach
    void retireVersionSuite() {
        fixture.sweep();
    }

    private List<Map<String, Object>> snapshots(long toolId) {
        return jdbcTemplate.queryForList(
                "SELECT version, reason, body FROM tp_tool_version WHERE tool_id = ? "
                        + "ORDER BY version", toolId);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void detailReturnsFullBodyUnlikeListPreview() {
        String longBody = "行内容\n".repeat(199) + "行内容";
        long id = fixture.createApprovedDocTool(toolService, 1L, "详情-全文", longBody);

        ToolInfo list = toolService.get(id);
        assertTrue(list.bodyPreview().endsWith("…"), "列表视图=截断预览（现状钉，防误改）");
        assertTrue(longBody.length() > list.bodyPreview().length());

        ToolDetail detail = toolService.getDetail(id);
        assertEquals(longBody, detail.body(), "detail=完整 body（断链修复①）");
        assertEquals(1, detail.currentVersion());
        assertEquals("描述", detail.description());
        assertEquals("取数", detail.category());
        assertThrows(ServiceException.class, () -> toolService.getDetail(999999L));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void downloadDocAsMarkdownSlugNamed() {
        long id = fixture.createApprovedDocTool(toolService, 1L, "文档 下载/Legacy", "# 标题\n正文\n");
        ToolDownloadFile file = toolService.download(1L, id, null);

        assertEquals("text/markdown", file.contentType());
        assertEquals("文档-下载-legacy-v1.md", file.fileName());
        assertEquals("# 标题\n正文", new String(file.content(), StandardCharsets.UTF_8),
                "create() body.trim() 既有冻结语义（存储无尾换行）");
        assertEquals(1, fixture.toolAuditCount("tool-download"), "下载审计恰 1");
    }

    @Test
    void downloadExecutableBundleImportRoundTripZeroLoss() throws Exception {
        ToolCreateInput input = new ToolCreateInput(null, "可执行-下载", 2, "分类", "执行描述",
                "附注正文", "[\"取数\",\"断言\"]",
                "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}",
                "{\"type\":\"string\"}", 1, "{\"url\":\"http://x/api\",\"authRef\":\"a1\"}", 45000,
                null);
        ToolInfo created = toolService.create(1L, input);
        fixture.tools().add(created.id());
        toolService.submit(1L, created.id(), created.version());
        toolService.approve(1L, created.id(), created.version() + 1);

        ToolDownloadFile file = toolService.download(1L, created.id(), null);
        assertEquals("application/json", file.contentType());
        assertEquals("可执行-下载-v1.tool-bundle.json", file.fileName());

        JsonNode bundle = objectMapper.readTree(file.content());
        for (String key : List.of("name", "description", "tags", "inputSchema", "outputSchema",
                "execKind", "execSpec", "timeoutMs", "body")) {
            assertTrue(bundle.has(key), "bundle 缺少契约字段 " + key);
        }
        assertEquals("可执行-下载", bundle.get("name").asText());
        assertEquals(2, bundle.get("type").asInt());
        assertEquals(1, bundle.get("execKind").asInt());
        assertEquals(45000, bundle.get("timeoutMs").asInt());
        assertEquals("http://x/api", bundle.get("execSpec").get("url").asText());

        ToolCreateInput reparsed = toolPackSupport.parseToCreateInput(
                null, "bundle.json", file.content());
        assertEquals(reparsed.name(), created.name());
        assertEquals(reparsed.type(), created.type());
        assertEquals(reparsed.category(), created.category());
        assertEquals(reparsed.description(), created.description());
        assertEquals(reparsed.body(), created.bodyPreview(), "回流 body 全量零丢失");
        assertEquals(objectMapper.readTree(reparsed.tagsJson()),
                objectMapper.readTree(created.tagsJson()));
        assertEquals(objectMapper.readTree(reparsed.inputSchemaJson()),
                objectMapper.readTree(created.inputSchemaJson()));
        assertEquals(objectMapper.readTree(reparsed.outputSchemaJson()),
                objectMapper.readTree(created.outputSchemaJson()));
        assertEquals(reparsed.execKind(), created.execKind());
        assertEquals(objectMapper.readTree(reparsed.execSpecJson()),
                objectMapper.readTree(created.execSpecJson()));
        assertEquals(reparsed.timeoutMs(), created.timeoutMs());
    }

    @Test
    void downloadSpecifiedVersionReadsSnapshotChain() {
        long id = fixture.createApprovedDocTool(toolService, 1L, "版本-下载", "旧版正文V1");
        ToolInfo current = toolService.get(id);
        toolService.update(1L, id, new ToolUpdateInput(current.version(),
                null, null, null, "新版正文V2", null, null, null, null, null, null));

        ToolDownloadFile v1 = toolService.download(1L, id, 1);
        assertEquals("旧版正文V1", new String(v1.content(), StandardCharsets.UTF_8),
                "指定 v1=链上快照源");
        assertTrue(v1.fileName().endsWith("-v1.md"));
        ToolDownloadFile v2 = toolService.download(1L, id, 2);
        assertEquals("新版正文V2", new String(v2.content(), StandardCharsets.UTF_8),
                "v2=现行值（链顶未快照=current 行）");
        ServiceException missing = assertThrows(ServiceException.class,
                () -> toolService.download(1L, id, 99));
        assertEquals(BizErrorCode.NOT_FOUND, missing.getCode(), "越界版本 40401 人话");
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void downloadInvalidVersionShapeAllRejectedNotFound() {
        long id = fixture.createApprovedDocTool(toolService, 1L, "版本-非法形态", "正文");
        for (int bad : new int[]{0, -1, 4242}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> toolService.download(1L, id, bad),
                    "非法版本 v" + bad + " 必须拒（0/负数/不存在三形态同径）");
            assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
            assertTrue(ex.getMessage().contains("不存在"), "人话「版本快照不存在」");
        }
    }

    @Test
    void downloadExecutableBadDataSucceedsAndManagementLegNeverCounts() {
        ToolInfo created = toolService.create(1L, new ToolCreateInput(null, "坏数据-下载", 2, "分类",
                "描述", "正文", null, null, null, 1,
                "{\"url\":\"http://x/api\"}", 5000, null));
        fixture.tools().add(created.id());
        toolService.submit(1L, created.id(), created.version());
        long id = toolService.approve(1L, created.id(), created.version() + 1).id();
        jdbcTemplate.update("UPDATE tp_tool SET exec_spec = NULL, download_count = 7 WHERE id = ?", id);

        ToolDownloadFile file = toolService.download(1L, id, null);
        assertEquals("application/json", file.contentType());
        JsonNode bundle = assertDoesNotThrow(() -> objectMapper.readTree(file.content()));
        assertEquals("坏数据-下载", bundle.get("name").asText());
        assertTrue(bundle.has("execKind"), "其余契约字段正常装配");
        assertTrue(!bundle.has("execSpec"), "坏数据字段省略=合法 bundle 形状，不 500");
        assertEquals(7, jdbcTemplate.queryForObject(
                "SELECT download_count FROM tp_tool WHERE id = ?", Integer.class, id).intValue(),
                "管理下载零计数");
    }

    @Test
    void retiredReadableButHardDeletedToolGoneFromAllReadLegs() {
        long id = fixture.createApprovedDocTool(toolService, 1L, "删除-失联", "终版正文");
        toolService.retire(1L, id, toolService.get(id).version());
        assertEquals("终版正文", toolService.getDetail(id).body(), "retired 仍可读（下架保留语义）");
        assertDoesNotThrow(() -> toolService.download(1L, id, null), "retired 可下载留档");

        toolService.delete(1L, id, toolService.get(id).version());
        assertThrows(ServiceException.class, () -> toolService.getDetail(id), "删除后 detail 404");
        assertThrows(ServiceException.class, () -> toolService.download(1L, id, null), "删除后 download 404");
        assertThrows(ServiceException.class, () -> toolService.listVersions(id, 1, 10), "删除后 versions 404");
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Test
    void rollbackRestoresSnapshotAndEntersReReview() {
        long id = fixture.createApprovedDocTool(toolService, 1L, "回滚-链", "A版正文");
        ToolInfo v1 = toolService.get(id);
        toolService.update(1L, id, new ToolUpdateInput(v1.version(),
                null, null, null, "B版正文", null, null, null, null, null, null));
        ToolInfo editing = toolService.get(id);
        toolService.approve(1L, id, editing.version());
        ToolInfo publishedV2 = toolService.get(id);
        assertEquals(2, publishedV2.currentVersion());

        ToolInfo after = toolService.rollback(1L, id,
                new ToolRollbackInput(1, publishedV2.version()));
        assertNotNull(after.id(), "回滚返回新态视图");
        ToolDetail restored = toolService.getDetail(id);
        assertEquals("A版正文", restored.body(), "快照覆盖当前值=v1 内容");
        assertEquals(3, restored.currentVersion(), "回滚生新版本号+1");
        assertEquals(2, restored.status(), "自动进既有重审链（pending_approval=2）");
        assertEquals("待审批", restored.statusLabel());
        assertNullFull(restored);

        List<Map<String, Object>> chain = snapshots(id);
        assertEquals(2, chain.size());
        assertEquals("edit", chain.get(0).get("reason"));
        assertEquals("rollback-pre", chain.get(1).get("reason"));
        assertEquals("B版正文", chain.get(1).get("body"));
        assertEquals(2, ((Number) chain.get(1).get("version")).intValue(),
                "rollback-pre 快照版本=被覆盖前的 current_version(2)——写点语义=旧值在 version=2");
        assertEquals(1, fixture.toolAuditCount("tool-rollback"));
    }

    
    private void assertNullFull(ToolDetail restored) {
        jdbcTemplate.queryForList("SELECT approved_by, approved_at, reject_reason FROM tp_tool "
                        + "WHERE id = ?", restored.id())
                .forEach(row -> {
                    assertNotNull(row);
                    assertTrue(row.get("approved_by") == null && row.get("approved_at") == null
                                    && row.get("reject_reason") == null,
                            "重审清旧审批留痕");
                });
    }

    @Test
    void rollbackOptimisticLockConflictWritesNothing() {
        long id = fixture.createApprovedDocTool(toolService, 1L, "回滚-锁", "A正文");
        ToolInfo v1 = toolService.get(id);
        toolService.update(1L, id, new ToolUpdateInput(v1.version(),
                null, null, null, "B正文", null, null, null, null, null, null));
        toolService.approve(1L, id, toolService.get(id).version());

        ToolInfo current = toolService.get(id);
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> toolService.rollback(1L, id,
                        new ToolRollbackInput(1, current.version() + 100)));
        assertEquals(BizErrorCode.VERSION_CONFLICT, conflict.getCode());
        assertEquals(1, snapshots(id).size(), "冲突=同事务回滚，rollback-pre 快照不留");
        assertEquals(0, fixture.toolAuditCount("tool-rollback"));
        ToolInfo unchanged = toolService.get(id);
        assertEquals(2, unchanged.currentVersion());
    }

    @Test
    void rollbackRejectsRetiredAndMissingVersion() {
        long id = fixture.createApprovedDocTool(toolService, 1L, "回滚-终态", "正文");
        toolService.retire(1L, id, toolService.get(id).version());
        ServiceException retired = assertThrows(ServiceException.class,
                () -> toolService.rollback(1L, id, new ToolRollbackInput(1, 999)));
        assertTrue(retired.getMessage().contains("工具"), "retired 终态拒回滚人话");

        long live = fixture.createApprovedDocTool(toolService, 1L, "回滚-缺版", "正文2");
        assertThrows(ServiceException.class,
                () -> toolService.rollback(1L, live, new ToolRollbackInput(5, 1)),
                "快照不存在 40401 人话");
    }
}
