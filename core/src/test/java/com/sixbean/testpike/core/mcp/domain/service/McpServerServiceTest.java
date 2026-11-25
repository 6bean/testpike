package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.mcp.api.dto.McpServerCreateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpServerInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpServerQuery;
import com.sixbean.testpike.core.mcp.api.dto.McpServerUpdateInput;
import com.sixbean.testpike.core.mcp.api.dto.McpToolSelectionInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.prod-blacklist=jira.prod.example.com")
class McpServerServiceTest {

    @Autowired
    private McpServerService mcpServerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCipher configCipher;

    /** Per-run name token: this run's servers are the only rows its teardown deletes (R37). */
    private final String token = SharedDbFixtureSweep.newToken();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        java.util.List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_mcp_server WHERE name LIKE ?", Long.class, token + "-%");
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_mcp_server", ids);
    }

    private McpServerCreateInput input(String name, String endpoint, Integer transport,
                                       String auth) {
        return new McpServerCreateInput(token + "-" + name, endpoint, transport, auth);
    }

    /** Counts only this run's servers (name-token scoped, replaces the old blanket TRUNCATE premise). */
    private int countServers() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mcp_server WHERE name LIKE ?", Integer.class, token + "-%");
        return n == null ? 0 : n;
    }

    private int auditCount(String action, long id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'mcp_server' "
                        + "AND action = ? AND object_id = ?",
                Integer.class, action, String.valueOf(id));
        return n == null ? 0 : n;
    }

    private String authEncOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT auth_enc FROM tp_mcp_server WHERE id = ?", String.class, id);
    }


    @Test
    void 校验三连_非URL_transport枚举外_auth超4KB各400零落库() {
        ServiceException badUrl = assertThrows(ServiceException.class,
                () -> mcpServerService.create(1L, input("合法名", "not-a-url", 2, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badUrl.getCode());
        assertTrue(badUrl.getMessage().contains("端点"), "文案人话中文口径（MCP 端点）: " + badUrl.getMessage());

        ServiceException ftpUrl = assertThrows(ServiceException.class,
                () -> mcpServerService.create(1L, input("合法名", "ftp://mcp.internal/sse", 2, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ftpUrl.getCode());

        ServiceException badTransport = assertThrows(ServiceException.class,
                () -> mcpServerService.create(1L, input("合法名", "http://localhost:9001/mcp", 3, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badTransport.getCode());
        assertTrue(badTransport.getMessage().contains("3"), "报错须含非法值: " + badTransport.getMessage());
        assertTrue(badTransport.getMessage().contains("streamable-http"),
                "报错须含可选枚举: " + badTransport.getMessage());

        ServiceException bigAuth = assertThrows(ServiceException.class,
                () -> mcpServerService.create(1L,
                        input("合法名", "http://localhost:9001/mcp", 1, "字".repeat(4097))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, bigAuth.getCode());
        assertTrue(bigAuth.getMessage().contains("4KB"), bigAuth.getMessage());

        assertEquals(0, countServers(), "全部拒绝后零落库");
    }


    @Test
    void SSRF_metadataIP与生产黑名单命中各400人话() {
        ServiceException meta = assertThrows(ServiceException.class,
                () -> mcpServerService.create(1L,
                        input("元数据探针", "http://169.254.169.254/latest/meta-data/", 2, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, meta.getCode());
        assertTrue(meta.getMessage().contains("169.254.169.254") || meta.getMessage().contains("元数据"),
                "须人话点破 metadata SSRF: " + meta.getMessage());

        ServiceException blacklisted = assertThrows(ServiceException.class,
                () -> mcpServerService.create(1L,
                        input("黑名单探针", "http://jira.prod.example.com/mcp", 2, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, blacklisted.getCode());
        assertTrue(blacklisted.getMessage().contains("黑名单"), blacklisted.getMessage());

        assertEquals(0, countServers(), "SSRF 拦截零落库");
    }


    @Test
    void 重名40904_全局唯一约束() {
        mcpServerService.create(1L, input("Jira MCP", "http://localhost:9001/mcp", 2, null));
        ServiceException dup = assertThrows(ServiceException.class,
                () -> mcpServerService.create(1L, input("Jira MCP", "http://localhost:9002/mcp", 1, null)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, dup.getCode());
        assertTrue(dup.getMessage().contains("Jira MCP"), dup.getMessage());
        assertEquals(1, countServers());
    }


    @Test
    void auth密文落库_出参打码_明文不入任何视图() {
        McpServerInfo created = mcpServerService.create(1L,
                input("知识库 MCP", "http://localhost:9003/mcp", 2, "Bearer sk-secret-token-abc"));
        assertEquals(ConfigCipher.MASK, created.authMasked(), "创建回显即打码");

        String enc = authEncOf(created.id());
        assertNotNull(enc);
        assertFalse(enc.contains("sk-secret-token-abc"), "DB 列禁存明文");
        assertEquals("Bearer sk-secret-token-abc", configCipher.decrypt(enc), "K9 件可解=同一加密件");

        McpServerInfo viewed = mcpServerService.get(created.id());
        assertEquals(ConfigCipher.MASK, viewed.authMasked());
        assertFalse(viewed.toString().contains("sk-secret-token-abc"), "视图禁含明文");

        McpServerInfo open = mcpServerService.create(1L,
                input("开放 MCP", "http://localhost:9004/mcp", 2, null));
        assertNull(open.authMasked());
        assertNull(authEncOf(open.id()));
    }


    @Test
    void 编辑_auth空保留原密文_非空替换_回显恒打码() {
        McpServerInfo created = mcpServerService.create(1L,
                input("TestRail MCP", "http://localhost:9005/mcp", 1, "token-old"));
        String encBefore = authEncOf(created.id());

        mcpServerService.update(1L, created.id(),
                new McpServerUpdateInput(null, null, null, null));
        assertEquals(encBefore, authEncOf(created.id()), "auth=null 保留原密文");
        McpServerInfo afterBlank = mcpServerService.update(1L, created.id(),
                new McpServerUpdateInput(token + "-TestRail MCP v2", null, null, "  "));
        assertEquals(encBefore, authEncOf(created.id()), "auth 空白=保留（编辑非清空通道）");
        assertEquals(token + "-TestRail MCP v2", afterBlank.name());
        assertEquals(ConfigCipher.MASK, afterBlank.authMasked());

        mcpServerService.update(1L, created.id(), new McpServerUpdateInput(null, null, null, "token-new"));
        String encAfter = authEncOf(created.id());
        assertFalse(encAfter.equals(encBefore));
        assertEquals("token-new", configCipher.decrypt(encAfter));

        ServiceException badEp = assertThrows(ServiceException.class,
                () -> mcpServerService.update(1L, created.id(),
                        new McpServerUpdateInput(null, "http://169.254.169.254/mcp", 9, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badEp.getCode());
        assertThrows(ServiceException.class,
                () -> mcpServerService.update(1L, 999_999L, new McpServerUpdateInput("n", null, null, null)));
    }


    @Test
    void 列表_筛选分页_排序白名单400() {
        mcpServerService.create(1L, input("Alpha 知识库", "http://localhost:9101/mcp", 2, null));
        McpServerInfo beta = mcpServerService.create(1L,
                input("Beta Jira", "http://localhost:9102/mcp", 2, "t"));
        mcpServerService.toggle(1L, beta.id(), 0);

        // List totals are read against the shared dev table, so every page below is
        // narrowed by the run token as its keyword: only this run's rows can match.
        PageResult<McpServerInfo> all = mcpServerService.list(
                new McpServerQuery(1, 20, token, null, null, null, null));
        assertEquals(2L, all.total());
        assertEquals(2, all.list().size());

        assertEquals(1L, mcpServerService.list(
                new McpServerQuery(1, 20, token + "-Alpha", null, null, null, null)).total(), "关键词命中名称");
        assertEquals(1L, mcpServerService.list(
                new McpServerQuery(1, 20, token, 0, null, null, null)).total(), "停用筛选");
        assertEquals(1L, mcpServerService.list(
                new McpServerQuery(1, 20, token, 1, null, null, null)).total(), "启用筛选");
        assertEquals(2L, mcpServerService.list(
                new McpServerQuery(1, 20, token, null, 0, null, null)).total(), "健康=未测试筛选");
        assertEquals(0L, mcpServerService.list(
                new McpServerQuery(1, 20, token, null, 1, null, null)).total(), "健康=健康筛选（本包零握手）");

        ServiceException badSort = assertThrows(ServiceException.class,
                () -> mcpServerService.list(new McpServerQuery(1, 20, null, null, null, "drop table", null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badSort.getCode());
        assertTrue(badSort.getMessage().contains("白名单"), badSort.getMessage());
        List<McpServerInfo> asc = mcpServerService.list(
                new McpServerQuery(1, 20, token, null, null, "name", "asc")).list();
        assertEquals(token + "-Alpha 知识库", asc.get(0).name());
    }


    @Test
    void 启停切换_非法值400_不存在40401() {
        McpServerInfo created = mcpServerService.create(1L,
                input("Gamma", "http://localhost:9103/mcp", 2, null));
        assertEquals(1, created.enabled());

        McpServerInfo off = mcpServerService.toggle(1L, created.id(), 0);
        assertEquals(0, off.enabled());
        assertEquals("停用", off.enabledLabel());
        McpServerInfo on = mcpServerService.toggle(1L, created.id(), 1);
        assertEquals(1, on.enabled());
        assertEquals("启用", on.enabledLabel());

        ServiceException bad = assertThrows(ServiceException.class,
                () -> mcpServerService.toggle(1L, created.id(), 2));
        assertEquals(BizErrorCode.VALIDATION_ERROR, bad.getCode());
        assertThrows(ServiceException.class, () -> mcpServerService.toggle(1L, 999_999L, 0));
    }


    @Test
    void 删除_落库移除_再查40401_不存在40401() {
        McpServerInfo created = mcpServerService.create(1L,
                input("Delta", "http://localhost:9104/mcp", 1, "x"));
        mcpServerService.delete(1L, created.id());
        assertEquals(0, countServers());
        ServiceException gone = assertThrows(ServiceException.class,
                () -> mcpServerService.get(created.id()));
        assertEquals(BizErrorCode.NOT_FOUND, gone.getCode());
        assertThrows(ServiceException.class, () -> mcpServerService.delete(1L, 999_999L));
    }


    @Test
    void 审计_四动作各恰1条() {
        McpServerInfo created = mcpServerService.create(1L,
                input("Epsilon", "http://localhost:9105/mcp", 2, null));
        mcpServerService.update(1L, created.id(), new McpServerUpdateInput("Epsilon2", null, null, null));
        mcpServerService.toggle(1L, created.id(), 0);
        mcpServerService.delete(1L, created.id());
        assertEquals(1, auditCount("create", created.id()));
        assertEquals(1, auditCount("update", created.id()));
        assertEquals(1, auditCount("toggle", created.id()));
        assertEquals(1, auditCount("delete", created.id()));
    }


    @Test
    void 不存在服务_40401人话() {
        ServiceException e = assertThrows(ServiceException.class, () -> mcpServerService.get(424242L));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode());
        assertTrue(e.getMessage().contains("424242"), e.getMessage());
    }


    
    private long seedWithSnapshot(String name) {
        long id = mcpServerService.create(1L,
                input(name, "http://localhost:9201/mcp", 2, null)).id();
        jdbcTemplate.update("UPDATE tp_mcp_server SET tools_json = ?::jsonb WHERE id = ?",
                """
                [{"name":"alpha","description":"甲工具","inputSchemaDigest":"d1","enabled":false},\
                 {"name":"danger_shell","description":"乙工具","inputSchemaDigest":"d2","enabled":false}]""",
                id);
        return id;
    }

    private String toolsJsonOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT tools_json::text FROM tp_mcp_server WHERE id = ?", String.class, id);
    }

    @Test
    void 勾选保存_快照内翻转enabled_快照字段与顺序保留_审计恰1() throws Exception {
        long id = seedWithSnapshot("勾选服务");
        McpServerInfo after = mcpServerService.updateTools(1L, id,
                new McpToolSelectionInput(List.of("danger_shell", "alpha")));
        var arr = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(toolsJsonOf(id));
        assertEquals(2, arr.size());
        assertEquals("alpha", arr.get(0).path("name").asText());
        assertTrue(arr.get(0).path("enabled").asBoolean(), "勾选集内=enabled true");
        assertEquals("甲工具", arr.get(0).path("description").asText(), "快照字段零丢失");
        assertEquals("d1", arr.get(0).path("inputSchemaDigest").asText());
        assertTrue(arr.get(1).path("enabled").asBoolean());
        var viewArr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(after.toolsJson());
        assertTrue(viewArr.get(0).path("enabled").asBoolean(), "出参视图=勾选态已生效");
        assertEquals(1, auditCount("tools", id), "勾选动作审计恰 1");
    }

    @Test
    void 勾选清空_传空数组即全拒合法_白名单回到默认全拒() throws Exception {
        long id = seedWithSnapshot("清空服务");
        mcpServerService.updateTools(1L, id, new McpToolSelectionInput(List.of("alpha")));
        mcpServerService.updateTools(1L, id, new McpToolSelectionInput(List.of()));
        var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(toolsJsonOf(id));
        assertFalse(arr.get(0).path("enabled").asBoolean(), "清空后全拒（IT-MCP-06 默认全拒语义）");
        assertFalse(arr.get(1).path("enabled").asBoolean());
    }

    @Test
    void 快照外工具名_40001人话点名_原快照零改动零审计() {
        long id = seedWithSnapshot("越界服务");
        String before = toolsJsonOf(id);
        ServiceException e = assertThrows(ServiceException.class,
                () -> mcpServerService.updateTools(1L, id,
                        new McpToolSelectionInput(List.of("alpha", "ghost_tool"))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("ghost_tool"), "报错须点名快照外工具: " + e.getMessage());
        assertTrue(e.getMessage().contains("快照") || e.getMessage().contains("连接测试"),
                "须人话解释快照语义: " + e.getMessage());
        assertEquals(before, toolsJsonOf(id), "拒绝后原快照零改动");
        assertEquals(0, auditCount("tools", id), "拒绝不落审计");
    }

    @Test
    void 未测试快照_勾选拒_人话先连接测试() {
        McpServerInfo noSnapshot = mcpServerService.create(1L,
                input("未测服务", "http://localhost:9202/mcp", 1, null));
        ServiceException e = assertThrows(ServiceException.class,
                () -> mcpServerService.updateTools(1L, noSnapshot.id(),
                        new McpToolSelectionInput(List.of("anything"))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("连接测试"), "人话指路=先测试连接: " + e.getMessage());
    }

    @Test
    void 勾选_清单缺失400_不存在服务40401() {
        long id = seedWithSnapshot("边界服务");
        ServiceException nullList = assertThrows(ServiceException.class,
                () -> mcpServerService.updateTools(1L, id, new McpToolSelectionInput(null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, nullList.getCode());
        assertThrows(ServiceException.class,
                () -> mcpServerService.updateTools(1L, id, null));
        ServiceException gone = assertThrows(ServiceException.class,
                () -> mcpServerService.updateTools(1L, 999_999L, new McpToolSelectionInput(List.of())));
        assertEquals(BizErrorCode.NOT_FOUND, gone.getCode());
    }
}
