package com.sixbean.testpike.core.mcp.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mcp.api.dto.McpAssemblyServer;
import com.sixbean.testpike.core.mcp.api.dto.McpInvocationInput;
import com.sixbean.testpike.core.mcp.api.dto.McpServerCreateInput;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class McpConsumptionServiceTest {

    @Autowired
    private McpConsumptionService mcpConsumptionService;

    @Autowired
    private McpServerService mcpServerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Per-run name token: assembly-view assertions count only this run's servers. */
    private final String token = SharedDbFixtureSweep.newToken();
    /** Per-run task marker: invocation rows are tracked and swept by this id. */
    private final long fxTaskId = 77_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000);
    private final List<Long> serverIds = new java.util.ArrayList<>();

    private String fxName(String base) {
        return token + "-" + base;
    }

    /**
     * R37 teardown (replaces the blanket TRUNCATE this suite used to run):
     * deletes only this run's invocation rows (by task marker) and its own
     * servers with their FK descendants. tp_audit_log is append-only by
     * design and every assertion here keys on object ids, so fixture audit
     * rows are left to the product retention channel.
     */
    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        jdbcTemplate.update("DELETE FROM tp_tool_invocation WHERE task_id = ?", fxTaskId);
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_mcp_server", serverIds);
        serverIds.clear();
    }

    // ---- helpers ----

    private static McpInvocationInput aiCall(Long taskId, long serverId, String toolName,
                                             Integer success) {
        return new McpInvocationInput("ai", null, taskId, null, serverId, toolName, success,
                1234L, null, "abcd1234", null);
    }

 
    private long registerWithTools(String name, String toolsJson, int enabled) {
        var info = mcpServerService.create(1L, new McpServerCreateInput(
                fxName(name), "http://127.0.0.1:19001/mcp", 2, "plain-secret-" + name));
        jdbcTemplate.update("UPDATE tp_mcp_server SET tools_json = ?::jsonb, enabled = ?,"
                + " health_status = 1, last_probe_at = now() WHERE id = ?", toolsJson, enabled, info.id());
        serverIds.add(info.id());
        return info.id();
    }


    @Test
    void 装配视图_仅启用服务进候选_勾选集过滤_未测试零工具() throws Exception {
        long on = registerWithTools("kb-mcp-on", """
                [{"name":"kb_search","description":"知识库检索","enabled":true},
                 {"name":"shell_exec","description":"执行任意 shell","enabled":false}]
                """, 1);
        registerWithTools("kb-mcp-off", "[{\"name\":\"kb_search\",\"enabled\":true}]", 0);
        var noProbe = mcpServerService.create(1L, new McpServerCreateInput(
                fxName("never-probed"), "http://127.0.0.1:19002/mcp", 2, null));
        serverIds.add(noProbe.id());

        List<McpAssemblyServer> servers = mcpConsumptionService.getAssemblyServers().stream()
                .filter(s -> s.name().startsWith(token)).toList();

        assertEquals(2, servers.size(), "停用服务不得进装配视图；未测试服务进视图但零工具");
        McpAssemblyServer first = servers.stream()
                .filter(s -> s.serverId().equals(on)).findFirst().orElseThrow();
        assertEquals(fxName("kb-mcp-on"), first.name());
        assertEquals("http://127.0.0.1:19001/mcp", first.endpoint());
        assertEquals(2, first.transport());
        assertEquals(List.of("kb_search"), first.toolNames(), "勾选集外工具不进装配候选（IT-MCP-06）");
        assertEquals("plain-secret-kb-mcp-on", first.auth());

        McpAssemblyServer unprobed = servers.stream()
                .filter(s -> s.serverId().equals(noProbe.id())).findFirst().orElseThrow();
        assertTrue(unprobed.toolNames().isEmpty(), "未测试（快照 NULL）=零工具可消费");
        assertNull(unprobed.auth(), "无鉴权服务 auth 透传 null");
    }


    @Test
    void 留痕批量落库_caller_ai_含任务与耗时_返回实写数() throws Exception {
        long serverId = registerWithTools("kb-mcp", "[{\"name\":\"kb_search\",\"enabled\":true}]", 1);

        int written = mcpConsumptionService.recordInvocations(List.of(
                aiCall(fxTaskId, serverId, "kb_search", 1),
                aiCall(fxTaskId, serverId, "kb_search", 0)));
        assertEquals(2, written);

        JsonNode ok = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT to_jsonb(t)::text AS j FROM tp_tool_invocation t WHERE task_id = "
                        + fxTaskId + " ORDER BY id LIMIT 1",
                String.class));
        assertEquals("ai", ok.get("caller").asText());
        assertEquals(fxTaskId, ok.get("task_id").asLong());
        assertEquals(serverId, ok.get("server_id").asLong());
        assertEquals("kb_search", ok.get("tool_name").asText());
        assertEquals(1, ok.get("success").asInt());
        assertEquals(1234L, ok.get("duration_ms").asLong());
        assertEquals(sha256Hex("abcd1234"), ok.get("digest_hash").asText());
    }

    private static String sha256Hex(String input) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }


    @Test
    void 形状校验族_触库前拒绝_零写入() {
        long serverId = registerWithTools("kb-mcp", "[{\"name\":\"kb_search\",\"enabled\":true}]", 1);

        assertThrows(ServiceException.class, () -> mcpConsumptionService.recordInvocations(
                List.of(new McpInvocationInput("robot", null, fxTaskId, null, serverId, "kb_search", 1,
                        null, null, null, null))));
        assertThrows(ServiceException.class, () -> mcpConsumptionService.recordInvocations(
                List.of(new McpInvocationInput("ai", null, fxTaskId, null, serverId, "kb_search", 7,
                        null, null, null, null))));
        assertThrows(ServiceException.class, () -> mcpConsumptionService.recordInvocations(
                List.of(new McpInvocationInput("ai", null, fxTaskId, null, serverId, "  ", 1,
                        null, null, null, null))));
        assertThrows(ServiceException.class, () -> mcpConsumptionService.recordInvocations(
                List.of(new McpInvocationInput("ai", null, fxTaskId, null, null, "kb_search", 1,
                        null, null, null, null))));
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool_invocation WHERE task_id = " + fxTaskId,
                Integer.class);
        assertEquals(0, n, "校验必须触库前拒绝（半批禁入）");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> mcpConsumptionService.recordInvocations(
                        List.of(new McpInvocationInput("x", null, fxTaskId, null, serverId, "kb_search", 1,
                                null, null, null, null))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertFalse(ex.getMessage().isBlank());
    }


    @Test
    void 留痕_caller_mcp_内置工具_双空豁免_按tool_name落库() throws Exception {
        int written = mcpConsumptionService.recordInvocations(List.of(
                new McpInvocationInput("mcp", 42L, fxTaskId, null, null, "create_case", 1,
                        88L, null, "{\"title\":\"t\"}", null)));
        assertEquals(1, written);

        JsonNode row = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT to_jsonb(t)::text AS j FROM tp_tool_invocation t WHERE task_id = "
                        + fxTaskId + " ORDER BY id LIMIT 1",
                String.class));
        assertEquals("mcp", row.get("caller").asText());
        assertEquals(42L, row.get("actor_id").asLong(), "actor=Token 属主（V42 裁定④）");
        assertTrue(row.get("server_id").isNull(), "内置工具无 server 实体引用");
        assertTrue(row.get("tool_id").isNull(), "内置工具无 tp_tool 实体引用");
        assertEquals("create_case", row.get("tool_name").asText());
        assertEquals(sha256Hex("{\"title\":\"t\"}"), row.get("digest_hash").asText());

        assertThrows(ServiceException.class, () -> mcpConsumptionService.recordInvocations(
                List.of(new McpInvocationInput("user", 1L, null, null, null, "some_tool", 1,
                        null, null, null, null))));
    }

    @Test
    void 空批零写不报错() {
        assertEquals(0, mcpConsumptionService.recordInvocations(List.of()));
        assertEquals(0, mcpConsumptionService.recordInvocations(null));
    }


    @Test
    void 留痕引用后禁删_清痕后可删_40904人话含引用数() {
        long serverId = registerWithTools("kb-mcp", "[{\"name\":\"kb_search\",\"enabled\":true}]", 1);
        mcpConsumptionService.recordInvocations(List.of(aiCall(fxTaskId, serverId, "kb_search", 1)));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> mcpServerService.delete(2L, serverId));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("1"), "人话须含引用数：" + ex.getMessage());

        jdbcTemplate.execute("DELETE FROM tp_tool_invocation WHERE server_id = " + serverId);
        mcpServerService.delete(2L, serverId);
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mcp_server WHERE id = ?", Integer.class, serverId);
        assertEquals(0, n);
    }
}
