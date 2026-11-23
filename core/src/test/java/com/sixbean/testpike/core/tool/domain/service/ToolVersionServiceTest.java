package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sixbean.testpike.core.tool.api.dto.ToolUpdateInput;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ToolVersionServiceTest {

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ToolSuiteSupport support;

    @BeforeEach
    void prepareFixtureSuite() {
        support = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of());
    }

    @AfterEach
    void sweepToolFixture() {
        support.sweep();
    }

    private List<Map<String, Object>> snapshots(long toolId) {
        return jdbcTemplate.queryForList(
                "SELECT version, reason, body, status_at_snapshot, created_by "
                        + "FROM tp_tool_version WHERE tool_id = ? ORDER BY version", toolId);
    }

    @Test
    void publishedEditSnapshotsOldValueBeforeOverwrite() {
        long id = support.createApprovedDocTool(toolService, 1L, "快照-edit", "旧版正文");
        ToolInfo current = toolService.get(id);
        assertTrue(snapshots(id).isEmpty(), "新建未编辑不应有快照");

        toolService.update(2L, id, new ToolUpdateInput(current.version(),
                null, null, null, "新版正文", null, null, null, null, null, null));

        List<Map<String, Object>> chain = snapshots(id);
        assertEquals(1, chain.size(), "published 改版=一快照");
        Map<String, Object> v1 = chain.get(0);
        assertEquals(1, ((Number) v1.get("version")).intValue(), "快照版本=被覆盖前的 current_version");
        assertEquals("旧版正文", v1.get("body"), "快照内容=被覆盖的旧值");
        assertEquals("edit", v1.get("reason"));
        assertEquals(3, ((Number) v1.get("status_at_snapshot")).intValue(), "快照产生时状态=published");
        assertEquals(2L, v1.get("created_by"));
        ToolInfo after = toolService.get(id);
        assertEquals(2, after.currentVersion(), "版本号码自此获得实体：链顶=current_version");
    }

    @Test
    void draftInPlaceEditWritesNoSnapshot() {
        ToolInfo created = toolService.create(1L, ToolSuiteSupport.docInput("快照-draft", "草稿正文一"));
        support.tools().add(created.id());
        toolService.update(1L, created.id(), new ToolUpdateInput(created.version(),
                null, null, null, "草稿正文二", null, null, null, null, null, null));
        assertTrue(snapshots(created.id()).isEmpty(),
                "draft 就地更新不生新版本，append-only 链无同版二写，零快照");
    }

    @Test
    void retireSnapshotsCurrentValue() {
        long id = support.createApprovedDocTool(toolService, 1L, "快照-retire", "终版正文");
        ToolInfo current = toolService.get(id);
        toolService.retire(1L, id, current.version());

        List<Map<String, Object>> chain = snapshots(id);
        assertEquals(1, chain.size());
        assertEquals(1, ((Number) chain.get(0).get("version")).intValue());
        assertEquals("终版正文", chain.get(0).get("body"));
        assertEquals("retire", chain.get(0).get("reason"));
        assertEquals(3, ((Number) chain.get(0).get("status_at_snapshot")).intValue(),
                "快照时状态=下架前 published");
    }

    @Test
    void versionsPageDescAndSingleVersionDetail() {
        long id = support.createApprovedDocTool(toolService, 1L, "快照-分页", "V1正文");
        ToolInfo c1 = toolService.get(id);
        toolService.update(1L, id, new ToolUpdateInput(c1.version(),
                null, null, null, "V2正文", null, null, null, null, null, null));
        ToolInfo p2 = toolService.get(id);
        toolService.approve(1L, id, p2.version());
        ToolInfo c2 = toolService.get(id);
        toolService.update(1L, id, new ToolUpdateInput(c2.version(),
                null, null, null, "V3正文", null, null, null, null, null, null));
        ToolInfo p3 = toolService.get(id);
        toolService.approve(1L, id, p3.version());
        ToolInfo c3 = toolService.get(id);
        toolService.update(1L, id, new ToolUpdateInput(c3.version(),
                null, null, null, "V4正文", null, null, null, null, null, null));

        PageResult<ToolVersionInfo> page1 = toolService.listVersions(id, 1, 2);
        assertEquals(3L, page1.total(), "v1/v2/v3 三快照（各=被覆盖前旧值）");
        assertEquals(2, page1.list().size());
        assertEquals(3, page1.list().get(0).version(), "倒序：链顶在前");
        assertEquals(2, page1.list().get(1).version());
        assertEquals("V3正文", page1.list().get(0).bodyPreview());

        ToolVersionDetail detail = toolService.getVersionSnapshot(id, 2);
        assertEquals(id, detail.toolId());
        assertEquals("V2正文", detail.body());
        assertEquals("edit", detail.reason());
        assertNull(detail.inputSchemaJson(), "DOC 类无 schema，快照零值透传");

        ServiceException missing = assertThrows(ServiceException.class,
                () -> toolService.getVersionSnapshot(id, 99));
        assertEquals(BizErrorCode.NOT_FOUND, missing.getCode());
        assertTrue(missing.getMessage().contains("v99"));
    }

    @Test
    void uniqueToolVersionIndexRejectsDuplicate() {
        long id = support.createApprovedDocTool(toolService, 1L, "快照-唯一", "正文");
        jdbcTemplate.update("INSERT INTO tp_tool_version (tool_id, version, body, "
                + "status_at_snapshot, reason) VALUES (?, 1, '手工', 3, 'edit')", id);
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(
                "INSERT INTO tp_tool_version (tool_id, version, body, "
                        + "status_at_snapshot, reason) VALUES (?, 1, '重复', 3, 'edit')", id),
                "uq_tool_ver(tool_id,version) 唯一兜底");
    }

    @Test
    void listVersionsRejectsMissingTool() {
        ServiceException missing = assertThrows(ServiceException.class,
                () -> toolService.listVersions(999_999L, 1, 10));
        assertEquals(BizErrorCode.NOT_FOUND, missing.getCode());
    }
}
