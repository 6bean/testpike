package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolQuery;
import com.sixbean.testpike.core.tool.api.dto.ToolUpdateInput;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Fixture discipline (R37): rows are tracked per run through
 * {@link ToolSuiteSupport} and deleted child-first at teardown — no blanket
 * TRUNCATE over the shipped factory rows, counts scoped by the run token.
 */
@SpringBootTest(classes = CoreApplication.class)
class ToolServiceTest {

    private static final String VALID_OBJECT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ToolSuiteSupport support;

    @BeforeEach
    void openToolServiceSuite() {
        support = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of());
    }

    @AfterEach
    void clearToolServiceSuite() {
        support.sweep();
    }

    private long createProject(String code) {
        return support.createProject(code);
    }

    private static ToolCreateInput doc(Long projectId, String name) {
        return new ToolCreateInput(projectId, name, 1, "取数", "测试数据规范", "正文内容",
                null, null, null, null, null, null, null);
    }

    private static ToolCreateInput http(Long projectId, String name, String execSpecJson) {
        return new ToolCreateInput(projectId, name, 2, "数据", null, null,
                null, VALID_OBJECT_SCHEMA, null, 1, execSpecJson, 30000, null);
    }


    @Test
    void 可执行缺exec_kind或exec_spec_400人话() {
        ToolCreateInput noKind = new ToolCreateInput(null, "无形态工具", 2, null, null, null,
                null, null, null, null, null, null, null);
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> toolService.create(1L, noKind));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e1.getCode());
        assertTrue(e1.getMessage().contains("执行形态"), e1.getMessage());

        ToolCreateInput noSpec = new ToolCreateInput(null, "缺spec工具", 2, null, null, null,
                null, null, null, 1, null, null, null);
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> toolService.create(1L, noSpec));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e2.getCode());
        assertTrue(e2.getMessage().contains("执行配置"), e2.getMessage());
        assertTrue(support.newToolIds().isEmpty(), "校验失败零落库");
    }

    @Test
    void HTTP缺url_EXECUTOR缺scriptEntry_MCP桥缺字段_逐项400() {
        ServiceException http = assertThrows(ServiceException.class,
                () -> toolService.create(1L, http(null, "取数HTTP", "{\"authRef\":\"k1\"}")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, http.getCode());
        assertTrue(http.getMessage().contains("url"), http.getMessage());

        ServiceException executor = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(null, "沙箱工具", 2, null, null,
                        null, null, null, null, 2, "{\"lang\":\"python\"}", null, null)));
        assertTrue(executor.getMessage().contains("scriptEntry"), executor.getMessage());

        ServiceException bridge = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(null, "桥工具", 2, null, null,
                        null, null, null, null, 3, "{\"serverId\":1}", null, null)));
        assertTrue(bridge.getMessage().contains("toolName"), bridge.getMessage());

        ServiceException bridge2 = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(null, "桥工具2", 2, null, null,
                        null, null, null, null, 3, "{\"toolName\":\"t\"}", null, null)));
        assertTrue(bridge2.getMessage().contains("serverId"), bridge2.getMessage());
        assertTrue(support.newToolIds().isEmpty(), "校验失败零落库");
    }

    @Test
    void timeout超上限_默认补齐_合法边界放行() {
        ServiceException tooLong = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(null, "超长超时", 2, null, null,
                        null, null, null, null, 1,
                        "{\"url\":\"http://x.example/api\"}", 600001, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, tooLong.getCode());
        assertTrue(tooLong.getMessage().contains("600000"), tooLong.getMessage());

        ToolInfo boundary = support.createTrackedTool(toolService, 1L,
                new ToolCreateInput(null, "边界超时", 2, null,
                        null, null, null, null, null, 1,
                        "{\"url\":\"http://x.example/api\"}", 600000, null));
        assertEquals(600000, boundary.timeoutMs());
        ToolInfo defaulted = support.createTrackedTool(toolService, 1L, http(null, "默认超时",
                "{\"url\":\"http://x.example/api\"}"));
        assertEquals(30000, defaulted.timeoutMs());
    }

    @Test
    void schema非法JSON_非Schema结构_逐项400() {
        ServiceException notJson = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(null, "坏JSON", 2, null, null,
                        null, null, "not-a-json{{", null, 1,
                        "{\"url\":\"http://x.example/api\"}", null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, notJson.getCode());
        assertTrue(notJson.getMessage().contains("input schema"), notJson.getMessage());

        ServiceException badDraft = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(null, "坏Schema", 2, null, null,
                        null, null, "{\"type\":\"not-a-type\"}", null, 1,
                        "{\"url\":\"http://x.example/api\"}", null, null)));
        assertTrue(badDraft.getMessage().contains("input schema"), badDraft.getMessage());

        ServiceException badOut = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(null, "坏出参", 2, null, null,
                        null, null, VALID_OBJECT_SCHEMA, "{\"type\":\"bogus\"}", 1,
                        "{\"url\":\"http://x.example/api\"}", null, null)));
        assertTrue(badOut.getMessage().contains("output schema"), badOut.getMessage());
        assertTrue(support.newToolIds().isEmpty(), "校验失败零落库");
    }


    @Test
    void DOC正文必填_type枚举外_名称族400() {
        ToolCreateInput noBody = new ToolCreateInput(null, "空正文", 1, null, null, "  ",
                null, null, null, null, null, null, null);
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> toolService.create(1L, noBody));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e1.getCode());
        assertTrue(e1.getMessage().contains("正文"), e1.getMessage());

        ServiceException badType = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(null, "类型6", 6, null, null,
                        null, null, null, null, null, null, null, null)));
        assertTrue(badType.getMessage().contains("文档类"), "报错含可选枚举: " + badType.getMessage());

        ServiceException nameTooLong = assertThrows(ServiceException.class,
                () -> toolService.create(1L, doc(null, "名".repeat(101))));
        assertTrue(nameTooLong.getMessage().contains("100"), nameTooLong.getMessage());

        ServiceException badKind = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(null, "形态9", 2, null, null,
                        null, null, null, null, 9, "{\"url\":\"http://x.example/api\"}",
                        null, null)));
        assertTrue(badKind.getMessage().contains("HTTP"), badKind.getMessage());
        assertTrue(support.newToolIds().isEmpty(), "校验失败零落库");
    }


    @Test
    void 重名40904_系统级桶与项目内_跨项目同名合法() {
        long pidA = createProject("TL-A");
        long pidB = createProject("TL-B");

        support.createTrackedTool(toolService, 1L, doc(null, "定位规范说明"));
        ServiceException sysDup = assertThrows(ServiceException.class,
                () -> toolService.create(1L, doc(null, "定位规范说明 " + support.token())));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, sysDup.getCode());
        assertTrue(sysDup.getMessage().contains("定位规范说明"), sysDup.getMessage());

        support.createTrackedTool(toolService, 1L, doc(pidA, "定位规范说明"));
        assertThrows(ServiceException.class,
                () -> toolService.create(1L, doc(pidA, "定位规范说明 " + support.token())));
        support.createTrackedTool(toolService, 1L, doc(pidB, "定位规范说明"));
        assertEquals(3, support.tools().size(), "系统级+两项目各一（uk COALESCE 桶语义）");
    }


    @Test
    void 审批全链_draft到published_审计恰1() {
        ToolInfo created = support.createTrackedTool(toolService, 1L, http(null, "造数服务",
                "{\"url\":\"http://x.example/api\"}"));
        assertEquals(ApprovalStatus.DRAFT.code(), created.status());
        assertEquals(1, created.currentVersion());
        assertEquals("草稿", created.statusLabel());
        assertNotNull(created.inputSchemaJson());

        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), submitted.status());

        ToolInfo approved = toolService.approve(2L, created.id(), submitted.version());
        assertEquals(ApprovalStatus.PUBLISHED.code(), approved.status());
        assertEquals("已发布", approved.statusLabel());
        assertEquals(2L, approved.approvedBy());
        assertNotNull(approved.approvedAt());
        assertEquals(1, support.toolAuditCount("approve", List.of(created.id())));

        ToolInfo retired = toolService.retire(2L, created.id(), approved.version());
        assertEquals(ApprovalStatus.RETIRED.code(), retired.status());
        ServiceException t = assertThrows(ServiceException.class,
                () -> toolService.approve(2L, created.id(), retired.version()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, t.getCode());
        assertTrue(t.getMessage().contains("工具已终止"), "公共件 entityLabel=工具: " + t.getMessage());
    }

    @Test
    void 已发布幂等approve_状态版本不动审计不重复() {
        ToolInfo created = support.createTrackedTool(toolService, 1L, doc(null, "已发布幂等"));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        ToolInfo approved = toolService.approve(2L, created.id(), submitted.version());

        ToolInfo again = toolService.approve(2L, created.id(), approved.version());
        assertEquals(ApprovalStatus.PUBLISHED.code(), again.status());
        assertEquals(approved.version(), again.version(), "幂等短路版本不 bump");
        assertEquals(1, support.toolAuditCount("approve", List.of(created.id())),
                "IT-SKL-03 口径：审计恰 1");
    }

    @Test
    void published改schema_版本bump自动重审() {
        ToolInfo created = support.createTrackedTool(toolService, 1L, http(null, "改版重审",
                "{\"url\":\"http://x.example/api\"}"));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        ToolInfo published = toolService.approve(2L, created.id(), submitted.version());

        ToolInfo updated = toolService.update(1L, created.id(), new ToolUpdateInput(
                published.version(), null, null, null, null, null,
                "{\"type\":\"object\",\"properties\":{\"q2\":{\"type\":\"string\"}}}",
                null, null, null, null));
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), updated.status(), "自动重审");
        assertEquals(2, updated.currentVersion());
        assertNull(updated.approvedAt(), "重审清旧审批留痕");

        ToolInfo published2 = toolService.approve(2L, created.id(), updated.version());
        ToolInfo meta = toolService.update(1L, created.id(), new ToolUpdateInput(
                published2.version(), null, null, "新描述", null, null, null, null,
                null, null, null));
        assertEquals(ApprovalStatus.PUBLISHED.code(), meta.status());
        assertEquals(2, meta.currentVersion(), "元数据编辑版本链不动");
        assertEquals("新描述", meta.description());
    }

    @Test
    void reject原因必填_留痕后重提合法() {
        ToolInfo created = support.createTrackedTool(toolService, 1L, doc(null, "将被拒"));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());

        ServiceException noReason = assertThrows(ServiceException.class,
                () -> toolService.reject(2L, created.id(), submitted.version(), "  "));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noReason.getCode());
        assertTrue(noReason.getMessage().contains("原因"), noReason.getMessage());

        ToolInfo rejected = toolService.reject(2L, created.id(), submitted.version(), "命名不规范");
        assertEquals(ApprovalStatus.REJECTED.code(), rejected.status());
        assertEquals("命名不规范", rejected.rejectReason());

        ToolInfo resubmitted = toolService.submit(1L, created.id(), rejected.version());
        assertEquals(ApprovalStatus.PENDING_APPROVAL.code(), resubmitted.status());
        assertNull(resubmitted.rejectReason(), "重提清拒绝原因");
    }

    @Test
    void 乐观锁陈旧40901() {
        ToolInfo created = support.createTrackedTool(toolService, 1L, doc(null, "并发工具"));
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> toolService.submit(1L, created.id(), 999));
        assertEquals(BizErrorCode.VERSION_CONFLICT, conflict.getCode());
        assertTrue(conflict.getMessage().contains("0"), "当前版本进人话: " + conflict.getMessage());
    }


    @Test
    void published禁直删_绑定项目409人话列名() {
        long pid = createProject("TL-BIND");
        ToolInfo created = support.createTrackedTool(toolService, 1L, doc(pid, "绑定中工具"));
        // a global count would mix the shipped factory rows into the picture.
        toolService.delete(1L, created.id(), created.version());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE id = ?", Integer.class, created.id()));

        ToolInfo pub = support.createTrackedTool(toolService, 1L, doc(pid, "发布后删"));
        ToolInfo sub = toolService.submit(1L, pub.id(), pub.version());
        ToolInfo ok = toolService.approve(2L, pub.id(), sub.version());

        ServiceException pubDel = assertThrows(ServiceException.class,
                () -> toolService.delete(1L, ok.id(), ok.version()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, pubDel.getCode());
        assertTrue(pubDel.getMessage().contains("不能直接删除"), pubDel.getMessage());

        jdbcTemplate.update("INSERT INTO tp_project_tool (project_id, tool_id) VALUES (?, ?)",
                pid, ok.id());
        ServiceException bound = assertThrows(ServiceException.class,
                () -> toolService.delete(1L, ok.id(), ok.version()));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, bound.getCode());
        assertTrue(bound.getMessage().contains("TL-BIND") || bound.getMessage().contains("项目TL-BIND"),
                "列引用项目名: " + bound.getMessage());
    }


    @Test
    void 列表筛选与排序白名单400() {
        long pid = createProject("TL-LIST");
        // The run token appears in both fixture names only, so every count
        // below is insensitive to shipped factory rows on the shared dev DB.
        support.createTrackedTool(toolService, 1L, doc(null, "文档甲"));
        support.createTrackedTool(toolService, 1L, http(pid, "HTTP乙", "{\"url\":\"http://x.example/api\"}"));

        assertEquals(2, toolService.list(new ToolQuery(1, 20, support.token(), null, null, null,
                null, null, null)).total());
        assertEquals(1, toolService.list(new ToolQuery(1, 20, support.token(), 1, null, null,
                null, null, null)).total(), "type=DOC 筛出 1");
        assertEquals(1, toolService.list(new ToolQuery(1, 20, support.token(), 2, null, pid,
                null, null, null)).total(), "type+projectId");
        assertEquals(1, toolService.list(new ToolQuery(1, 20, "文档甲 " + support.token(), null,
                null, null, null, null, null)).total(), "keyword 名称模糊");
        ServiceException sort = assertThrows(ServiceException.class,
                () -> toolService.list(new ToolQuery(1, 20, null, null, null, null,
                        "name; drop table", "asc", null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, sort.getCode());
    }
}
