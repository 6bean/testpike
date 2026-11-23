package com.sixbean.testpike.core.mcp.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mcp.api.dto.McpProbeResult;
import com.sixbean.testpike.core.mcp.api.dto.McpServerCreateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpServerInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpToolSelectionInput;
import com.sixbean.testpike.core.mcp.domain.component.McpToolSnapshot;
import com.sixbean.testpike.core.mcp.domain.component.MockMcpServerFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class McpConnectivityServiceTest {

    @Autowired
    private McpServerService mcpServerService;

    @Autowired
    private McpConnectivityService mcpConnectivityService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMcpServerFixture fixture;

    /** Fixture server ids created by this run; teardown deletes only these rows (R37). */
    private final java.util.List<Long> serverIds = new java.util.ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_mcp_server", serverIds);
        serverIds.clear();
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
    }

    private MockMcpServerFixture startFixture(String mode) throws IOException, InterruptedException {
        fixture = MockMcpServerFixture.start(mode);
        return fixture;
    }

    private Long register(String endpoint, String auth) {
        Long id = mcpServerService.create(1L,
                new McpServerCreateInput("mcp-" + System.nanoTime(), endpoint, 2, auth)).id();
        serverIds.add(id);
        return id;
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private String toolsJsonOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT tools_json::text FROM tp_mcp_server WHERE id = ?", String.class, id);
    }

    private Map<String, Object> healthOf(long id) {
        return jdbcTemplate.queryForList(
                "SELECT health_status, health_message, (last_probe_at IS NOT NULL) AS probed "
                        + "FROM tp_mcp_server WHERE id = ?", id).get(0);
    }


    @Test
    void 握手成功_落快照默认全拒_健康列与探测时间落库() throws Exception {
        MockMcpServerFixture fx = startFixture("normal");
        Long id = register(fx.endpoint(), null);
        assertNull(toolsJsonOf(id), "未测试态 tools_json 必 NULL（裁定③）");

        McpProbeResult result = mcpConnectivityService.test(1L, id);

        assertTrue(result.success(), "真实工装握手应成功: " + result.message());
        assertEquals("ok", result.category());
        assertNotNull(result.server().lastProbeAt(), "last_probe_at 应落值");
        String tools = toolsJsonOf(id);
        assertNotNull(tools, "tools_json 快照应落库");
        assertTrue(tools.contains("jira_get_issue") && tools.contains("shell_exec")
                && tools.contains("db_drop_table"), "5 工具快照含危险两件（勾选素材）: " + tools);
        assertTrue(tools.contains("\"enabled\": false") || tools.contains("\"enabled\":false")
                || tools.contains("\"enabled\" : false"),
                "白名单默认全拒（IT-MCP-06 素材面）: " + tools);
        Map<String, Object> h = healthOf(id);
        assertEquals(1, ((Number) h.get("health_status")).intValue(), "health_status=健康");
        assertTrue(Boolean.TRUE.equals(h.get("probed")), "last_probe_at 落列");
    }

    /**
     * The real handshake chain must carry tool annotations into the persisted
     * snapshot: the knowledge-sync read-only guard reads
     * {@code annotations.readOnlyHint} from tools_json, so any layer that drops
     * the field (handshake client parsing or snapshot serialization) silently
     * rejects every subscription creation. This assertion runs against the real
     * client + persistence path — not a hand-assembled snapshot fixture.
     */
    @Test
    void 握手成功_快照透传工具注解只读标记() throws Exception {
        MockMcpServerFixture fx = startFixture("normal");
        Long id = register(fx.endpoint(), null);

        McpProbeResult result = mcpConnectivityService.test(1L, id);
        assertTrue(result.success(), "真实工装握手应成功: " + result.message());
        String tools = toolsJsonOf(id);
        assertNotNull(tools, "tools_json 快照应落库");

        JsonNode arr = new ObjectMapper().readTree(tools);
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        arr.forEach(node -> byName.put(node.path("name").asText(""), node));
        assertTrue(byName.get("jira_get_issue").path("annotations").path("readOnlyHint").asBoolean(false),
                "只读工具的 readOnlyHint 必须随真实握手快照透传落库: " + tools);
        assertFalse(byName.get("shell_exec").path("annotations").path("readOnlyHint").asBoolean(true),
                "危险工具的 readOnlyHint=false 同样必须显式透传（缺席视同非只读，注解不可整块丢失）: " + tools);
        // Default-reject semantics stay intact: annotations never auto-enable a tool.
        assertEquals(List.of(), McpToolSnapshot.readOnlyEnabledNames(tools),
                "快照默认全拒：enabled 全 false 时只读白名单必空: " + tools);
    }

    /**
     * A successful re-probe refreshes the tool snapshot but must inherit the
     * admin-side enabled flags (G5 whitelist selection is a persisted operator
     * fact): the knowledge-sync DOWN gate probes periodically, so a raw
     * default-reject re-write silently wipes every opened read-only tool —
     * the drawer picker goes empty and sync rounds get rejected afterwards.
     * Regression pin: removing the merge fails this test.
     */
    @Test
    void 重新探测成功_保留已勾选工具开放态() throws Exception {
        MockMcpServerFixture fx = startFixture("normal");
        Long id = register(fx.endpoint(), null);
        assertTrue(mcpConnectivityService.test(1L, id).success(), "首次探测应成功建快照");

        mcpServerService.updateTools(1L, id, new McpToolSelectionInput(List.of("jira_get_issue")));
        String selected = toolsJsonOf(id);
        assertEquals(List.of("jira_get_issue"), McpToolSnapshot.readOnlyEnabledNames(selected),
                "勾选面素材：只读工具已开放: " + selected);

        McpProbeResult again = mcpConnectivityService.test(1L, id);
        assertTrue(again.success(), "再次探测应成功: " + again.message());

        String refreshed = toolsJsonOf(id);
        assertEquals(List.of("jira_get_issue"), McpToolSnapshot.readOnlyEnabledNames(refreshed),
                "重新探测必合并勾选态（保留开放，禁洗回全拒）: " + refreshed);
        // Merge never relaxes default-reject: un-selected tools stay closed.
        JsonNode arr = new ObjectMapper().readTree(refreshed);
        arr.forEach(node -> {
            String name = node.path("name").asText("");
            assertEquals("jira_get_issue".equals(name), node.path("enabled").asBoolean(false),
                    "除已勾选工具外其余元素必维持关闭态: " + name);
        });
    }


    @Test
    void 端点不可达_network_分类人话落列() throws Exception {
        Long id = register("http://127.0.0.1:" + closedPort() + "/mcp", null);

        McpProbeResult result = mcpConnectivityService.test(1L, id);

        assertFalse(result.success());
        assertEquals("network", result.category());
        assertTrue(result.message().contains("无法连接") || result.message().contains("不可达"),
                "人话中文: " + result.message());
        Map<String, Object> h = healthOf(id);
        assertEquals(2, ((Number) h.get("health_status")).intValue(), "失败=health 2");
        assertNotNull(h.get("health_message"), "health_message 人话落列");
    }

    @Test
    void 握手超时5s_protocol_分类预算生效() throws Exception {
        MockMcpServerFixture fx = startFixture("slow");
        Long id = register(fx.endpoint(), null);

        long t0 = System.currentTimeMillis();
        McpProbeResult result = mcpConnectivityService.test(1L, id);
        long elapsedSec = (System.currentTimeMillis() - t0) / 1000;

        assertFalse(result.success());
        assertEquals("protocol", result.category());
        assertTrue(elapsedSec <= 8, "5s 握手预算必生效（30s 工装被腰斩），实测 " + elapsedSec + "s");
        assertTrue(result.message().contains("超时"), "人话含超时: " + result.message());
    }

    @Test
    void 返回非法JSONRPC_protocol_分类() throws Exception {
        MockMcpServerFixture fx = startFixture("garbage");
        Long id = register(fx.endpoint(), null);

        McpProbeResult result = mcpConnectivityService.test(1L, id);

        assertFalse(result.success());
        assertEquals("protocol", result.category());
        assertTrue(result.message().contains("协议") || result.message().contains("非法"),
                "人话含协议面: " + result.message());
    }

    @Test
    void JSONRPC_error响应_protocol_分类含服务端消息() throws Exception {
        MockMcpServerFixture fx = startFixture("error");
        Long id = register(fx.endpoint(), null);

        McpProbeResult result = mcpConnectivityService.test(1L, id);

        assertFalse(result.success());
        assertEquals("protocol", result.category());
    }


    @Test
    void 鉴权凭证解密后_Bearer_透传至端点() throws Exception {
        MockMcpServerFixture fx = startFixture("normal");
        Long id = register(fx.endpoint(), "Bearer secret-" + "abc123");

        McpProbeResult result = mcpConnectivityService.test(1L, id);

        assertTrue(result.success(), "带鉴权握手应成功: " + result.message());
        assertTrue(fx.authLogContent().contains("Bearer secret-abc123"),
                "工装侧实档必须收到解密后的 Bearer 原值");
    }


    @Test
    void 注册后endpoint被篡改_metadata_IP_第二道闸拒且不发起连接() throws Exception {
        MockMcpServerFixture fx = startFixture("normal");
        Long id = register(fx.endpoint(), null);
        jdbcTemplate.update("UPDATE tp_mcp_server SET endpoint = 'http://169.254.169.254/mcp' "
                + "WHERE id = ?", id);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> mcpConnectivityService.test(1L, id));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("元数据"), "人话含元数据拦截: " + ex.getMessage());

        Map<String, Object> h = healthOf(id);
        assertEquals(0, ((Number) h.get("health_status")).intValue(), "第二道闸=拒在连接前，health 不被写");
    }

    @Test
    void 不存在的服务_40401() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> mcpConnectivityService.test(1L, 999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }
}
