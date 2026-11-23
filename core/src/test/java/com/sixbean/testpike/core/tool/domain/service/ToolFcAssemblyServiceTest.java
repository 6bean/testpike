package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingInput;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingItem;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolFcAssemblyItem;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import com.sixbean.testpike.core.tool.domain.service.ProjectToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Fixture discipline (R37): creations are tracked through
 * {@link ToolSuiteSupport} and assembly assertions run over the fixture-scoped
 * id view — the shipped factory rows stay on the database and never bias a
 * count (no blanket TRUNCATE).
 */
@SpringBootTest(classes = CoreApplication.class)
class ToolFcAssemblyServiceTest {

    @Autowired
    private ToolFcAssemblyService toolFcAssemblyService;

    @Autowired
    private ToolService toolService;

    @Autowired
    private ProjectToolService projectToolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    private ToolSuiteSupport support;

    @BeforeEach
    void openAssemblySuite() {
        support = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of("tp_user_project"));
    }

    @AfterEach
    void clearAssemblySuite() {
        support.sweep();
    }

    // ---- helpers ----

    private long createProject(String code) {
        return support.createProject(code);
    }

    /** HTTP EXECUTABLE tool walked to published (tracked rows, no hard-coded ids). */
    private long createPublishedHttpTool(Long projectId, String name, String inputSchemaJson) {
        return support.createPublishedTool(toolService, 1L, new ToolCreateInput(projectId, name, 2,
                "数据", null, null, null, inputSchemaJson, null, 1,
                "{\"url\":\"http://127.0.0.1:9/echo\"}", 30000, null)).id();
    }

    /** DOC-type tool walked to published (tracked). */
    private long createDocToolPublished(Long projectId, String name) {
        return support.createPublishedTool(toolService, 1L, new ToolCreateInput(projectId, name, 1,
                "取数", null, "正文内容", null, null, null, null, null, null, null)).id();
    }

    private void bind(long projectId, List<ProjectToolBindingItem> items) {
        projectToolService.saveBindings(1L, projectId, new ProjectToolBindingInput(items, null));
    }

    private ProjectToolBindingItem on(long toolId) {
        return new ProjectToolBindingItem(toolId, 1);
    }

    private ProjectToolBindingItem off(long toolId) {
        return new ProjectToolBindingItem(toolId, 0);
    }

    private ToolFcAssemblyItem itemOf(List<ToolFcAssemblyItem> list, long toolId) {
        return list.stream().filter(i -> i.toolId() == toolId).findFirst().orElseThrow();
    }

    /**
     * Fixture-scoped view of an assembly result: only this run's tracked tool
     * ids survive the filter, keeping every count assertion insensitive to
     * the shipped factory rows on the shared dev database.
     */
    private List<Long> fixtureIds(List<ToolFcAssemblyItem> items) {
        return items.stream()
                .map(ToolFcAssemblyItem::toolId)
                .filter(support.tools()::contains)
                .toList();
    }

    
    private static void assertJsonEqual(String expected, String actual, String message) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            assertEquals(m.readTree(expected), m.readTree(actual), message);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError(message + "（JSON 解析失败: " + e.getMessage() + "）", e);
        }
    }


    @Test
    void 装配集仅published且enabled_停用与绑定后下架排除() {
        long pid = createProject("FC01");
        long pub = createPublishedHttpTool(pid, "取数工具", "{\"type\":\"object\"}");
        long willRetire = createPublishedHttpTool(pid, "将下架工具", "{\"type\":\"object\"}");
        long stopped = createPublishedHttpTool(pid, "停用工具", "{\"type\":\"object\"}");
        bind(pid, List.of(on(pub), on(willRetire), off(stopped)));
        toolService.retire(2L, willRetire, toolService.get(willRetire).version());

        List<ToolFcAssemblyItem> items = toolFcAssemblyService.getAssemblyItems(pid);

        assertEquals(1, fixtureIds(items).size(), "仅 published∩enabled 进装配集（停用/绑定后下架排除）");
        assertEquals(pub, fixtureIds(items).get(0));
    }


    @Test
    void 契约列透传_schema原文与exec路由键与预算() {
        long pid = createProject("FC03");
        String schema = "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}";
        long http = createPublishedHttpTool(pid, "HTTP工具", schema);
        long doc = createDocToolPublished(pid, "文档工具");
        bind(pid, List.of(on(http), on(doc)));

        List<ToolFcAssemblyItem> items = toolFcAssemblyService.getAssemblyItems(pid);
        assertEquals(2, fixtureIds(items).size(), "文档类原样回（FC 清单筛除归 ai-service 编排单点，core 禁复制判型）");

        ToolFcAssemblyItem httpItem = itemOf(items, http);
        assertEquals(2, httpItem.type());
        assertEquals(1, httpItem.execKind(), "exec_kind=HTTP 路由键透传（ToolDispatcher 消费）");
        assertJsonEqual(schema, httpItem.inputSchemaJson(), "input_schema 语义透传（JSONB ::text 排版归一，禁按字符串等值断）");
        assertEquals(30000, httpItem.timeoutMs());
        assertFalse(httpItem.write(), "无 x-write=只读");

        ToolFcAssemblyItem docItem = itemOf(items, doc);
        assertEquals(1, docItem.type());
        assertEquals(null, docItem.execKind(), "文档类无执行形态");
    }


    @Test
    void xWrite显式true进装配视图写语义标记() {
        long pid = createProject("FC04");
        long w = createPublishedHttpTool(pid, "写工具",
                "{\"type\":\"object\",\"x-write\":true}");
        long r = createPublishedHttpTool(pid, "显式只读工具",
                "{\"type\":\"object\",\"x-write\":false}");
        bind(pid, List.of(on(w), on(r)));

        List<ToolFcAssemblyItem> items = toolFcAssemblyService.getAssemblyItems(pid);
        assertTrue(itemOf(items, w).write(), "x-write=true → 确认门开（PRD U4）");
        assertFalse(itemOf(items, r).write());
    }


    @Test
    void 读取序按toolId升序确定性() {
        long pid = createProject("FC05");
        long a = createPublishedHttpTool(pid, "先建", "{\"type\":\"object\"}");
        long b = createPublishedHttpTool(pid, "后建", "{\"type\":\"object\"}");
        bind(pid, List.of(on(b), on(a)));

        List<ToolFcAssemblyItem> items = toolFcAssemblyService.getAssemblyItems(pid);
        assertEquals(List.of(a, b), fixtureIds(items));
    }

    @Test
    void 未绑定空列表_项目不存在40401() {
        long pid = createProject("FC06");
        assertTrue(fixtureIds(toolFcAssemblyService.getAssemblyItems(pid)).isEmpty());

        ServiceException e = assertThrows(ServiceException.class,
                () -> toolFcAssemblyService.getAssemblyItems(999999L));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode(),
                "项目不存在 40401（SkillConsumptionService 同口径）");
    }

    // ---- ⑥ AO batch stage 1: five-way gate, project-visibility facet ----
    // Horizontal spectrum (member vs non-member) and vertical cross-role
    // (system admin) for the actor-aware assembly overload; the single-arg
    // legacy overload must stay behaviour-identical .

    /**
     * Member row helper (test-only user id band >= 900000; the row is swept
     * with the tracked fixture project through tp_user_project).
     */
    private void addMember(long projectId, long userId, int role) {
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) "
                + "VALUES (?, ?, ?)", projectId, userId, role);
    }

    @Test
    void 装配带actor_成员放行_非成员40301零泄漏_旧重载不受判权() {
        long pid = createProject("FC07");
        long tool = createPublishedHttpTool(pid, "成员装配", "{\"type\":\"object\"}");
        bind(pid, List.of(on(tool)));
        long member = 900001L;
        addMember(pid, member, 1);
        long stranger = 900002L;   // no membership row anywhere

        List<ToolFcAssemblyItem> asMember =
                toolFcAssemblyService.getAssemblyItems(pid, member);
        assertEquals(1, fixtureIds(asMember).size(), "项目成员装配集=published∩enabled 原口径");

        ServiceException err = assertThrows(ServiceException.class,
                () -> toolFcAssemblyService.getAssemblyItems(pid, stranger));
        assertEquals(BizErrorCode.FORBIDDEN, err.getCode(),
                "非成员水平越权 40301（裁决件 §四：拒且零泄漏）");
        assertFalse(err.getMessage().contains("FC07"),
                "拒词禁点名项目内容（零泄漏面）: " + err.getMessage());

        // legacy overload: no actor, no verdict — chain zero drift
        assertEquals(1, fixtureIds(toolFcAssemblyService.getAssemblyItems(pid)).size(),
                "旧单参重载行为不变（判权仅随 actor 重载生效）");
    }

    @Test
    void 装配带actor_系统管理员放行全部切片() {
        long pid = createProject("FC08");
        long tool = createPublishedHttpTool(pid, "管理员装配", "{\"type\":\"object\"}");
        bind(pid, List.of(on(tool)));
        // temporary admin row (role=4 legacy enum, picked up by the shared
        // AdminAlertRecipients verdict — reuse, never duplicate the admin set)
        Long adminId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, display_name, role, status, deleted) "
                        + "VALUES ('fc_gate_tmp_admin', '飞书告警临时管理员', 4, 1, 0) RETURNING id",
                Long.class);
        try {
            List<ToolFcAssemblyItem> items =
                    toolFcAssemblyService.getAssemblyItems(pid, adminId);
            assertEquals(1, fixtureIds(items).size(), "系统管理员放行全部项目切片（复用 AdminAlertRecipients 判定）");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", adminId);
        }
    }

    // ---- ⑦ five-way gate, execution-class facet (vertical spectrum) ----

    /** EXECUTOR-shape published tool (exec_kind=2 = execution-class family). */
    private long createPublishedExecutorTool(long projectId, String name) {
        return support.createPublishedTool(toolService, 1L, new ToolCreateInput(projectId, name, 2,
                "沙箱", null, "print('x')", null, "{\"type\":\"object\"}", null, 2,
                "{\"scriptEntry\":\"main.py\"}", 30000, null)).id();
    }

    @Test
    void 装配带actor_exec开关关_EXECUTOR缺席_HTTP与文档类两态不受影响() {
        long pid = createProject("FC09");
        long http = createPublishedHttpTool(pid, "HTTP不受闸", "{\"type\":\"object\"}");
        long executor = createPublishedExecutorTool(pid, "执行类受闸");
        bind(pid, List.of(on(http), on(executor)));
        long member = 900003L;
        addMember(pid, member, 1);
        // The execution switch ships ON (master AI feature rule); capture the
        // live value so cleanup restores it instead of the pre-ruling hard-coded
        // 'false', which used to leave the shared dev DB switched off for every
        // later consumer of the row.
        String execSwitchBefore = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config "
                        + "WHERE config_key = 'ai.chat.exec-enabled'", String.class);
        try {
            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'false' "
                    + "WHERE config_key = 'ai.chat.exec-enabled'");
            configCacheHolder.put("ai.chat.exec-enabled", "false");
            List<ToolFcAssemblyItem> offItems = toolFcAssemblyService.getAssemblyItems(pid, member);
            assertEquals(1, fixtureIds(offItems).size(), "开关关=EXECUTOR 装配缺席（执行类开关维收窄）");
            assertEquals(http, fixtureIds(offItems).get(0), "HTTP 形态不受执行闸影响（负谱）");

            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'true' "
                    + "WHERE config_key = 'ai.chat.exec-enabled'");
            configCacheHolder.put("ai.chat.exec-enabled", "true");
            List<ToolFcAssemblyItem> onItems = toolFcAssemblyService.getAssemblyItems(pid, member);
            assertEquals(2, fixtureIds(onItems).size(), "开关开=EXECUTOR 回场（两态谱另一半）");

            // legacy overload keeps the old published∩enabled-only behaviour
            assertEquals(2, fixtureIds(toolFcAssemblyService.getAssemblyItems(pid)).size(),
                    "旧单参重载不做执行类收窄（生成链零漂移）");
        } finally {
            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ? "
                    + "WHERE config_key = 'ai.chat.exec-enabled'", execSwitchBefore);
            configCacheHolder.put("ai.chat.exec-enabled", execSwitchBefore);
        }
    }
}
