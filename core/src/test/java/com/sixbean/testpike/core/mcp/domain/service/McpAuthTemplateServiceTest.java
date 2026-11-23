package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mcp.api.dto.McpAssemblyServer;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthApplyInput;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthApplyResult;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthTemplateInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpAuthTemplateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpServerCreateInput;
import com.sixbean.testpike.core.mcp.api.dto.ProjectMcpAuthInfo;
import com.sixbean.testpike.core.mcp.api.dto.ProjectMcpAuthInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class McpAuthTemplateServiceTest {

    @Autowired
    private McpAuthTemplateService mcpAuthTemplateService;

    @Autowired
    private McpServerService mcpServerService;

    @Autowired
    private McpConsumptionService mcpConsumptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    
    private static final long PROJ = 990001L;

    /** Per-run name token: teardown deletes only rows whose names carry it (R37). */
    private final String token = SharedDbFixtureSweep.newToken();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_project_mcp_auth WHERE project_id = ?", PROJ);
        jdbcTemplate.update("DELETE FROM tp_mcp_auth_template WHERE name LIKE ?", token + "-%");
        java.util.List<Long> sids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_mcp_server WHERE name LIKE ?", Long.class, token + "-%");
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_mcp_server", sids);
    }

    // ---- helpers ----

    private McpAuthTemplateInput templateInput(String name, List<String> tools,
                                               Integer scope, Long projectId) {
        return new McpAuthTemplateInput(token + "-" + name, tools, "测试模板", scope, projectId);
    }

    
    private long registerWithTools(String serverName, String toolsJson) {
        var info = mcpServerService.create(1L, new McpServerCreateInput(
                token + "-" + serverName, "http://127.0.0.1:19001/mcp", 2, null));
        jdbcTemplate.update("UPDATE tp_mcp_server SET tools_json = ?::jsonb, "
                + "health_status = 1, last_probe_at = now() WHERE id = ?", toolsJson, info.id());
        return info.id();
    }

    private List<String> enabledNamesOf(long serverId) {
        String json = jdbcTemplate.queryForObject(
                "SELECT tools_json::text FROM tp_mcp_server WHERE id = ?", String.class, serverId);
        try {
            var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            List<String> names = new java.util.ArrayList<>();
            arr.forEach(n -> {
                if (n.path("enabled").asBoolean(false)) {
                    names.add(n.path("name").asText());
                }
            });
            return names;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Audit rows are append-only; assertions key on this run's object id instead (R37). */
    private int auditCount(String action, long templateId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'mcp_auth_template' "
                        + "AND action = ? AND object_id = ?",
                Integer.class, action, String.valueOf(templateId));
        return n == null ? 0 : n;
    }


    @Test
    void 模板创建重名_409人话() {
        long tid = mcpAuthTemplateService.create(1L,
                templateInput("只读模板", List.of("kb_search"), 1, null)).id();
        ServiceException dup = assertThrows(ServiceException.class,
                () -> mcpAuthTemplateService.create(1L,
                        templateInput("只读模板", List.of("doc_read"), 1, null)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode());
        assertTrue(dup.getMessage().contains("只读模板"), "重名人话须点名模板名: " + dup.getMessage());
        assertEquals(1, auditCount("create", tid));
    }

    @Test
    void 项目级模板缺项目ID_400() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> mcpAuthTemplateService.create(1L,
                        templateInput("项目模板", List.of("kb_search"), 2, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("项目"), "人话须解释项目级必填项目: " + ex.getMessage());
    }

    @Test
    void 模板改名撞重名_409且原名保留可编辑() {
        long a = mcpAuthTemplateService.create(1L,
                templateInput("A", List.of("kb_search"), 1, null)).id();
        long b = mcpAuthTemplateService.create(1L,
                templateInput("B", List.of("doc_read"), 1, null)).id();
        assertThrows(ServiceException.class, () -> mcpAuthTemplateService.update(1L, b,
                templateInput("A", List.of("doc_read"), 1, null)));
        McpAuthTemplateInfo renamed = mcpAuthTemplateService.update(1L, b,
                templateInput("B2", List.of("doc_read", "kb_search"), 1, null));
        assertEquals(token + "-B2", renamed.name());
        assertEquals(List.of("doc_read", "kb_search"), renamed.tools());
        assertNotNull(renamed.updatedAt());
    }


    @Test
    void 模板删除被项目继承引用_409人话_解除后可删() {
        long tid = mcpAuthTemplateService.create(1L,
                templateInput("被引用", List.of("kb_search"), 1, null)).id();
        mcpAuthTemplateService.putProjectAuth(1L, PROJ, new ProjectMcpAuthInput(tid, 1));
        ServiceException blocked = assertThrows(ServiceException.class,
                () -> mcpAuthTemplateService.delete(1L, tid));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, blocked.getCode());
        assertTrue(blocked.getMessage().contains("继承"), "人话须指路先解除继承: " + blocked.getMessage());
        jdbcTemplate.update("DELETE FROM tp_project_mcp_auth WHERE project_id = ?", PROJ);
        mcpAuthTemplateService.delete(1L, tid);
        assertNull(mcpAuthTemplateService.getProjectAuth(PROJ));
        assertEquals(1, auditCount("delete", tid));
    }


    @Test
    void 套用模板_并集不取消_快照外工具跳过点名() {
        long serverId = registerWithTools("mcp-a", """
                [{"name":"kb_search","enabled":false},
                 {"name":"doc_read","enabled":true},
                 {"name":"extra_kept","enabled":true}]
                """);
        long tid = mcpAuthTemplateService.create(1L, templateInput("套装",
                List.of("kb_search", "ghost_tool"), 1, null)).id();

        McpAuthApplyResult result = mcpAuthTemplateService.apply(1L, tid,
                new McpAuthApplyInput(List.of(serverId, 999999L)));

        assertEquals(1, result.updatedServers(), "不存在的目标服务不计数");
        assertEquals(List.of("kb_search", "doc_read", "extra_kept"), enabledNamesOf(serverId),
                "并集只置 true：模板新启用 kb_search，模板外已勾选 extra_kept/doc_read 不取消");
        String joined = String.join("；", result.details());
        assertTrue(joined.contains("ghost_tool"), "快照外工具必须人话点名: " + joined);
        assertTrue(joined.contains("mcp-a"), "details 按服务点名: " + joined);
        assertTrue(joined.contains("不存在"), "不存在服务人话点名: " + joined);
        assertEquals(1, auditCount("apply", tid));
    }

    @Test
    void 套用模板_未测试服务跳过人话() {
        var neverProbed = mcpServerService.create(1L, new McpServerCreateInput(
                token + "-未测试", "http://127.0.0.1:19003/mcp", 2, null));
        long tid = mcpAuthTemplateService.create(1L,
                templateInput("套装2", List.of("kb_search"), 1, null)).id();

        McpAuthApplyResult result = mcpAuthTemplateService.apply(1L, tid,
                new McpAuthApplyInput(List.of(neverProbed.id())));
        assertEquals(0, result.updatedServers());
        assertTrue(String.join("；", result.details()).contains("连接测试"),
                "未测试快照=空，应人话引导先连接测试: " + result.details());
    }


    @Test
    void 装配_项目无继承配置_直通零漂移() {
        registerWithTools("mcp-on", "[{\"name\":\"kb_search\",\"enabled\":true}]");
        List<McpAssemblyServer> scoped = mcpConsumptionService.getAssemblyServers(PROJ);
        assertEquals(mcpConsumptionService.getAssemblyServers(), scoped,
                "无继承配置的项目=直通全局装配（存量零漂移）");
    }

    @Test
    void 装配_继承关闭_零工具服务不进装配() {
        registerWithTools("mcp-on", "[{\"name\":\"kb_search\",\"enabled\":true}]");
        long tid = mcpAuthTemplateService.create(1L,
                templateInput("T", List.of("kb_search"), 1, null)).id();
        mcpAuthTemplateService.putProjectAuth(1L, PROJ, new ProjectMcpAuthInput(tid, 0));

        assertTrue(mcpConsumptionService.getAssemblyServers(PROJ).isEmpty(),
                "enabled=0 → 该项目生成链零 MCP 工具");
        assertEquals(1, mcpConsumptionService.getAssemblyServers().stream()
                .filter(s -> s.name().startsWith(token)).count());
    }

    @Test
    void 装配_继承开启_勾选集与模板名集交集保序() {
        registerWithTools("mcp-on", """
                [{"name":"doc_read","enabled":true},{"name":"kb_search","enabled":true}]
                """);
        long tid = mcpAuthTemplateService.create(1L,
                templateInput("只读套装", List.of("kb_search"), 1, null)).id();
        mcpAuthTemplateService.putProjectAuth(1L, PROJ, new ProjectMcpAuthInput(tid, 1));

        List<McpAssemblyServer> scoped = mcpConsumptionService.getAssemblyServers(PROJ);
        assertEquals(1, scoped.size());
        assertEquals(List.of("kb_search"), scoped.get(0).toolNames(),
                "交集=勾选集∩模板名集（保快照原序）");
        ProjectMcpAuthInfo cfg = mcpAuthTemplateService.getProjectAuth(PROJ);
        assertEquals(tid, cfg.templateId());
        assertEquals(token + "-只读套装", cfg.templateName());
        assertEquals(1, cfg.enabled());
    }

    @Test
    void 装配_模板JSON坏值_failsoft直通不炸链路() {
        registerWithTools("mcp-on", "[{\"name\":\"kb_search\",\"enabled\":true}]");
        long tid = mcpAuthTemplateService.create(1L,
                templateInput("坏模板", List.of("kb_search"), 1, null)).id();
        jdbcTemplate.update("UPDATE tp_mcp_auth_template SET tools = '\"oops-string\"'::jsonb WHERE id = ?", tid);
        mcpAuthTemplateService.putProjectAuth(1L, PROJ, new ProjectMcpAuthInput(tid, 1));

        List<McpAssemblyServer> global = mcpConsumptionService.getAssemblyServers();
        assertEquals(global, mcpConsumptionService.getAssemblyServers(PROJ),
                "坏模板 JSON 不得炸装配链路，回退直通并告警");
    }

    @Test
    void 项目继承配置_模板不存在_404人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> mcpAuthTemplateService.putProjectAuth(1L, PROJ,
                        new ProjectMcpAuthInput(888888L, 1)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        long tid = mcpAuthTemplateService.create(1L,
                templateInput("T", List.of("kb_search"), 1, null)).id();
        assertEquals(BizErrorCode.VALIDATION_ERROR, assertThrows(ServiceException.class,
                () -> mcpAuthTemplateService.putProjectAuth(1L, PROJ,
                        new ProjectMcpAuthInput(tid, 7))).getCode());
    }
}
