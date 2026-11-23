package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.mcp.infrastructure.remote.McpCaller;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpToolGateway;
import com.sixbean.testpike.api.mcp.infrastructure.remote.RestCoreConfigClient;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class McpServerHandlerTest {

    
    private static final class RecordingGateway implements McpToolGateway {
        private List<com.sixbean.testpike.api.mcp.infrastructure.remote.McpPassThroughRemoteTool>
                passThroughTools = List.of();
        private String lastEndpointKey;
        private int passThroughCalls;
        private String lastTool;
        private Map<String, Object> lastArguments;
        private long lastUserId;
        private int calls;
        private String response = "{\"caseId\":777,\"status\":\"待评审\"}";
        private RuntimeException failure;
        private int tokenUsageCalls;
        private long lastTokenUsageId;
        private long lastTokenUsageOperator;
        private String tokenUsageResponse =
                "{\"tokenId\":77,\"calls\":12,\"failures\":2,\"p95DurationMs\":812.5}";

        @Override
        public String tokenUsage(long tokenId, long operatorUserId) {
            calls++;
            tokenUsageCalls++;
            lastTokenUsageId = tokenId;
            lastTokenUsageOperator = operatorUserId;
            if (failure != null) {
                throw failure;
            }
            return tokenUsageResponse;
        }

        @Override
        public List<com.sixbean.testpike.api.mcp.infrastructure.remote.McpPassThroughRemoteTool>
                listPassThroughTools() {
            return passThroughTools;
        }

        @Override
        public String invokePassThrough(String endpointKey, Map<String, Object> arguments,
                                        long operatorUserId) {
            calls++;
            passThroughCalls++;
            lastEndpointKey = endpointKey;
            lastArguments = arguments;
            lastUserId = operatorUserId;
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public String invoke(String toolName, Map<String, Object> arguments, McpCaller caller) {
            calls++;
            lastTool = toolName;
            lastArguments = arguments;
            lastUserId = caller.userId();
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
    private final JsonSchemaValidator schemaValidator = McpJsonDefaults.getSchemaValidator();
    private final AtomicBoolean readonly = new AtomicBoolean(false);
    private final RecordingGateway gateway = new RecordingGateway();
    private final RecordingRecordClient records = new RecordingRecordClient();
    private final StringRedisTemplate redis = newRedis();
    private final McpServerHandler handler = newHandler();

    
    private static final class RecordingRecordClient
            implements com.sixbean.testpike.api.mcp.infrastructure.remote.ToolInvocationRecordClient {
        private final List<com.sixbean.testpike.api.mcp.infrastructure.remote.McpInvocationRemoteInput> all =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void record(List<com.sixbean.testpike.api.mcp.infrastructure.remote.McpInvocationRemoteInput> invocations) {
            all.addAll(invocations);
        }
    }

    private static StringRedisTemplate newRedis() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    /** Governance-key stand-in: this class tests dispatch, convergence and idempotency only. */
    private static RestCoreConfigClient stubConfigClient() {
        RestCoreConfigClient client = mock(RestCoreConfigClient.class);
        when(client.secondsSupplier(anyString(), anyLong()))
                .thenReturn(() -> McpPassThroughToolRegistry.KEY_REFRESH_SECONDS != null ? 300L : 300L);
        return client;
    }

    private McpServerHandler newHandler() {
        McpToolCatalog catalog = new McpToolCatalog();
        return new McpServerHandler(catalog, new McpToolVisibilityFilter(catalog),
                new McpPassThroughToolRegistry(gateway, stubConfigClient()),
                jsonMapper, schemaValidator, readonly::get, gateway, new IdempotentGuard(redis),
                records);
    }

    private static McpTransportContext context(McpPrincipal principal, String idempotencyKey) {
        Map<String, Object> map = new HashMap<>();
        if (principal != null) {
            map.put(McpContextKeys.PRINCIPAL, principal);
        }
        if (idempotencyKey != null) {
            map.put(McpContextKeys.IDEMPOTENCY_KEY, idempotencyKey);
        }
        return McpTransportContext.create(map);
    }

    private McpSchema.JSONRPCResponse call(McpTransportContext ctx, String method, Object params) {
        return handler.handleRequest(ctx,
                new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, method, 1L, params)).block();
    }

    private McpSchema.JSONRPCResponse callTool(McpTransportContext ctx, String tool, Map<String, Object> args) {
        return call(ctx, McpSchema.METHOD_TOOLS_CALL, new McpSchema.CallToolRequest(tool, args));
    }

    private McpSchema.ListToolsResult listTools(McpTransportContext ctx) {
        McpSchema.JSONRPCResponse response = call(ctx, McpSchema.METHOD_TOOLS_LIST, Map.of());
        assertNull(response.error(), String.valueOf(response.error()));
        return jsonMapper.convertValue(response.result(), McpSchema.ListToolsResult.class);
    }

    
    private static void assertRpcError(McpSchema.JSONRPCResponse response, int code, String messageContains) {
        assertNotNull(response.error(), "应为 JSON-RPC error 应答");
        assertEquals(code, response.error().code(), "错误码: " + response.error().message());
        assertTrue(response.error().message().contains(messageContains),
                "人话须含「" + messageContains + "」: " + response.error().message());
        assertNull(response.result());
    }

    private McpSchema.CallToolResult toolResult(McpSchema.JSONRPCResponse response) {
        assertNull(response.error(), "应答应为 result 而非协议错误: " + response.error());
        return jsonMapper.convertValue(response.result(), McpSchema.CallToolResult.class);
    }

    @Test
    void initialize_返回固定协议应答与工具能力() {
        McpSchema.JSONRPCResponse response = call(context(null, null), McpSchema.METHOD_INITIALIZE, Map.of());
        assertNull(response.error());
        McpSchema.InitializeResult result =
                jsonMapper.convertValue(response.result(), McpSchema.InitializeResult.class);
        assertEquals(McpSchema.LATEST_PROTOCOL_VERSION, result.protocolVersion());
        assertNotNull(result.capabilities().tools(), "须声明 tools 能力");
        assertEquals("testpike", result.serverInfo().name());
        assertNotNull(result.instructions(), "人话 instructions 须有");
    }

    @Test
    void 无认证上下文_请求一律拒_防绕过transport() {
        assertRpcError(call(context(null, null), McpSchema.METHOD_TOOLS_LIST, Map.of()),
                McpSchema.ErrorCodes.INVALID_REQUEST, "认证");
    }

    @Test
    void tools_list_全量scope_九件齐且schema翻译() {
        McpSchema.ListToolsResult result =
                listTools(context(new McpPrincipal(9L, List.of("mcp.case", "mcp.read")), null));
        assertEquals(10, result.tools().size());
        McpSchema.Tool create = result.tools().stream()
                .filter(t -> t.name().equals("create_case")).findFirst().orElseThrow();
        assertEquals("object", create.inputSchema().type());
        assertTrue(create.inputSchema().properties().containsKey("project_id"));
        assertTrue(create.description().contains("待评审"), "对外承诺文案不得丢: " + create.description());
    }

    @Test
    void tools_list_mcp_mock_scope_两mock写件现身() {
        McpSchema.ListToolsResult result =
                listTools(context(new McpPrincipal(9L, List.of("mcp.mock")), null));
        List<String> names = result.tools().stream().map(McpSchema.Tool::name).toList();
        assertEquals(Set.of("create_mock_stub", "publish_mock_space"), Set.copyOf(names));
    }

    @Test
    void tools_list_只读scope_写件从列表消失() {
        McpSchema.ListToolsResult result =
                listTools(context(new McpPrincipal(9L, List.of("mcp.read")), null));
        List<String> names = result.tools().stream().map(McpSchema.Tool::name).toList();
        assertEquals(7, names.size(), "只留七读件: " + names);
        assertFalse(names.contains("create_case"));
        assertFalse(names.contains("tag_case"));
        assertFalse(names.contains("delete_case"));
        assertFalse(names.contains("create_mock_stub"));
        assertFalse(names.contains("publish_mock_space"));
    }

    @Test
    void tools_list_readonly开关_写件消失可热切() {
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case", "mcp.read")), null);
        assertEquals(10, listTools(ctx).tools().size());
        readonly.set(true);
        McpSchema.ListToolsResult after = listTools(ctx);
        assertEquals(7, after.tools().size(), "只读态写工具从列表消失（非仅调用拒绝）");
    }

    /** Projected pass-through tools (generated by core from its scanned catalog). */
    private void givenPassThroughTools() {
        gateway.passThroughTools = List.of(
                new com.sixbean.testpike.api.mcp.infrastructure.remote.McpPassThroughRemoteTool(
                        "api_get_internal_cases",
                        "直读本系统接口 GET /internal/cases；可填参数：projectId、keyword。",
                        "{\"type\":\"object\",\"properties\":{\"projectId\":{\"type\":\"integer\"},"
                                + "\"keyword\":{\"type\":\"string\"}},"
                                + "\"required\":[\"projectId\"],\"additionalProperties\":false}",
                        "GET /internal/cases"),
                new com.sixbean.testpike.api.mcp.infrastructure.remote.McpPassThroughRemoteTool(
                        "api_get_internal_tags", "直读 GET /internal/tags；归属项目按参数 projectId 判定。",
                        "{\"type\":\"object\",\"properties\":{\"projectId\":{\"type\":\"integer\"}},"
                                + "\"required\":[\"projectId\"],\"additionalProperties\":false}",
                        "GET /internal/tags"));
    }

    @Test
    void tools_list_直通目录件随读scope现身_无读scope即消失() {
        givenPassThroughTools();
        McpSchema.ListToolsResult withRead =
                listTools(context(new McpPrincipal(9L, List.of("mcp.read")), null));
        List<String> names = withRead.tools().stream().map(McpSchema.Tool::name).toList();
        assertTrue(names.contains("api_get_internal_cases"), "目录实扫出的条目须出现在对外面: " + names);
        assertTrue(names.contains("api_get_internal_tags"), "两枚目录件都要在: " + names);
        assertEquals(7 + 2, names.size(), "七读件+两直通件: " + names);

        McpSchema.ListToolsResult caseOnly =
                listTools(context(new McpPrincipal(9L, List.of("mcp.case")), null));
        List<String> caseNames = caseOnly.tools().stream().map(McpSchema.Tool::name).toList();
        assertFalse(caseNames.contains("api_get_internal_cases"),
                "直通件与读件同 scope，列表层就收敛（非调用时才拒）: " + caseNames);
    }

    @Test
    void tools_call_直通件按目录键分派_属主透传_不读参数外壳() {
        givenPassThroughTools();
        McpSchema.CallToolResult result = toolResult(callTool(
                context(new McpPrincipal(9L, List.of("mcp.read")), null),
                "api_get_internal_cases", Map.of("projectId", 42, "keyword", "登录")));
        assertFalse(result.isError() != null && result.isError(), String.valueOf(result));
        assertEquals(1, gateway.passThroughCalls);
        assertEquals("GET /internal/cases", gateway.lastEndpointKey,
                "对外工具名背后的目录键必须由投影携带，不能由名字反推");
        assertNull(gateway.lastTool, "直通件不得走静态工具分派面");
        assertEquals(9L, gateway.lastUserId, "属主必须是认证 Token 属主");
        assertEquals(42, gateway.lastArguments.get("projectId"));
    }

    @Test
    void tools_call_直通件参数不合目录声明_结构化拒含字段名() {
        givenPassThroughTools();
        McpSchema.JSONRPCResponse missing = callTool(
                context(new McpPrincipal(9L, List.of("mcp.read")), null),
                "api_get_internal_tags", Map.of("keyword", "x"));
        assertRpcError(missing, McpSchema.ErrorCodes.INVALID_PARAMS, "projectId");
        assertEquals(0, gateway.passThroughCalls, "不合目录声明的参数不该打到门上");
    }

    @Test
    void tools_call_令牌用量件经出示令牌行路由() {
        McpSchema.CallToolResult result = toolResult(callTool(
                context(new McpPrincipal(9L, 77L, List.of("mcp.read")), null),
                "tokens.usage", Map.of()));
        assertFalse(result.isError() != null && result.isError(), String.valueOf(result));
        assertEquals(1, gateway.tokenUsageCalls, "用量件走专用聚合读面一跳，无参数无项目");
        assertEquals(0, gateway.passThroughCalls, "静态目录件不得落直通面");
        assertEquals(77L, gateway.lastTokenUsageId, "令牌行号必须是本次认证出示的那一行");
        assertEquals(9L, gateway.lastTokenUsageOperator, "操作人=Token 属主，归属复判在 core 单点");
        assertTrue(String.valueOf(result).contains("p95DurationMs"),
                "core 聚合结果原样透出: " + result);
    }

    @Test
    void tools_call_令牌用量件无令牌身份诚实拒止零外呼() {
        McpSchema.JSONRPCResponse response = callTool(
                context(new McpPrincipal(9L, List.of("mcp.read")), null),
                "tokens.usage", Map.of());
        assertRpcError(response, McpSchema.ErrorCodes.INVALID_PARAMS, "Token");
        assertEquals(0, gateway.tokenUsageCalls, "未达执行面的拒止零外呼");
        assertEquals(0, gateway.calls, "拒止不产生任何网关调用");
    }

    @Test
    void tools_call_直通件对无读scope属主_与未知工具同口径拒() {
        givenPassThroughTools();
        assertRpcError(callTool(context(new McpPrincipal(9L, List.of("mcp.case")), null),
                "api_get_internal_cases", Map.of("projectId", 1)),
                McpSchema.ErrorCodes.METHOD_NOT_FOUND, "not found");
    }

    @Test
    void tools_call_未知工具与scope外写件_同文案防探测() {
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.read")), null);
        McpSchema.JSONRPCResponse unknown = callTool(ctx, "no_such_tool", Map.of("project_id", 1));
        McpSchema.JSONRPCResponse scopeOut = callTool(ctx, "create_case", Map.of("project_id", 1));
        assertRpcError(unknown, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "not found");
        assertRpcError(scopeOut, McpSchema.ErrorCodes.METHOD_NOT_FOUND, "not found");
        assertEquals(unknown.error().message(), scopeOut.error().message(),
                "防探测：scope 外与未知工具文案必须不可区分");
        assertEquals(0, gateway.calls, "拒止路径零触执行面");
    }

    @Test
    void tools_call_参数不合schema_结构化拒_人话含字段名() {
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case")), "k-" + UUID.randomUUID());
        Map<String, Object> missingTitle = new HashMap<>();
        missingTitle.put("project_id", 1);
        assertRpcError(callTool(ctx, "create_case", missingTitle),
                McpSchema.ErrorCodes.INVALID_PARAMS, "title");
        assertEquals(0, gateway.calls, "schema 不合不得打 core");
    }

    @Test
    void tools_call_写工具缺幂等键_拒且人话含键名() {
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case")), null);
        Map<String, Object> args = Map.of("project_id", 1, "title", "t", "steps", "s", "expected", "e");
        assertRpcError(callTool(ctx, "create_case", args),
                McpSchema.ErrorCodes.INVALID_PARAMS, "Idempotency-Key");
        assertEquals(0, gateway.calls, "缺键拒止零外呼");
    }

    @Test
    void tools_call_writeToolKeyFromArgument_opensTheWriteDoor() {
        // A generic MCP bridge cannot attach a per-request header, so the argument channel is the
        // only way an agent conversation can reach a write tool. Headerless context must succeed.
        String idemKey = "arg-" + UUID.randomUUID();
        McpTransportContext headerless = context(new McpPrincipal(42L, List.of("mcp.case")), null);
        Map<String, Object> args = new HashMap<>();
        args.put("project_id", 7);
        args.put("title", "argument channel");
        args.put("steps", "s");
        args.put("expected", "e");
        args.put(McpIdempotencyKey.ARGUMENT, idemKey);
        try {
            assertFalse(Boolean.TRUE.equals(toolResult(callTool(headerless, "create_case", args)).isError()),
                    "参数通道须开门");
            assertEquals(1, gateway.calls, "带键即执行一次");
            assertFalse(gateway.lastArguments.containsKey(McpIdempotencyKey.ARGUMENT),
                    "传输通道键不得转进 core 业务参数");
            assertEquals(7, gateway.lastArguments.get("project_id"), "业务参数照旧透传");
        } finally {
            redis.delete("idempotent:" + McpServerHandler.idempotentKeyForTest("create_case", idemKey));
        }
    }

    @Test
    void tools_call_writeToolKeyInsideMetaSlot_accepted() {
        String idemKey = "meta-" + UUID.randomUUID();
        McpTransportContext headerless = context(new McpPrincipal(42L, List.of("mcp.case")), null);
        Map<String, Object> args = new HashMap<>();
        args.put("project_id", 8);
        args.put("title", "meta channel");
        args.put("steps", "s");
        args.put("expected", "e");
        args.put(McpIdempotencyKey.META_ARGUMENT, Map.of(McpIdempotencyKey.META_KEY, idemKey));
        try {
            assertFalse(Boolean.TRUE.equals(toolResult(callTool(headerless, "create_case", args)).isError()));
            assertEquals(1, gateway.calls, "spec 元数据槽同开门");
            assertFalse(gateway.lastArguments.containsKey(McpIdempotencyKey.META_ARGUMENT),
                    "元数据槽不入业务参数");
        } finally {
            redis.delete("idempotent:" + McpServerHandler.idempotentKeyForTest("create_case", idemKey));
        }
    }

    @Test
    void tools_call_headerChannelKeepsPriority_whenArgumentAlsoPresent() {
        String headerKey = "hdr-" + UUID.randomUUID();
        McpTransportContext ctx = context(new McpPrincipal(42L, List.of("mcp.case")), headerKey);
        Map<String, Object> args = new HashMap<>();
        args.put("project_id", 9);
        args.put("title", "priority");
        args.put("steps", "s");
        args.put("expected", "e");
        args.put(McpIdempotencyKey.ARGUMENT, "arg-" + UUID.randomUUID());
        try {
            assertFalse(Boolean.TRUE.equals(toolResult(callTool(ctx, "create_case", args)).isError()));
            Map<String, Object> headerOnly = new HashMap<>(args);
            headerOnly.remove(McpIdempotencyKey.ARGUMENT);
            assertFalse(Boolean.TRUE.equals(toolResult(callTool(
                    context(new McpPrincipal(42L, List.of("mcp.case")), headerKey),
                    "create_case", headerOnly)).isError()));
            assertEquals(1, gateway.calls, "头通道优先：第二发须回放同键，不得另立一键");
        } finally {
            redis.delete("idempotent:" + McpServerHandler.idempotentKeyForTest("create_case", headerKey));
        }
    }

    @Test
    void tools_list_writeTools_advertiseIdempotencyArgument_readToolsDoNot() {
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case", "mcp.read")), null);
        Set<String> writeNames = Set.copyOf(new McpToolCatalog().all().stream()
                .filter(McpToolDefinition::writeTool).map(McpToolDefinition::name).toList());
        assertFalse(writeNames.isEmpty(), "夹具须含写件");
        for (McpSchema.Tool tool : listTools(ctx).tools()) {
            boolean advertised = String.valueOf(tool.inputSchema()).contains(McpIdempotencyKey.ARGUMENT);
            assertEquals(writeNames.contains(tool.name()), advertised,
                    "幂等参数须恰好在写件 schema 出现: " + tool.name());
        }
    }

    @Test
    void tools_call_missingKey_rejectionNamesTheChannelTheClientCanUse() {
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case")), null);
        Map<String, Object> args = Map.of("project_id", 1, "title", "t", "steps", "s", "expected", "e");
        String message = callTool(ctx, "create_case", args).error().message();
        assertTrue(message.contains(McpIdempotencyKey.ARGUMENT), "拒因须点名可照做的参数: " + message);
        assertTrue(message.contains("重复"), "拒因须说明同键与换键的后果: " + message);
    }

    @Test
    void tools_call_create_case成功_网关收参数属主_重复幂等键回放不双建() throws Exception {
        String idemKey = "mcp-it-" + UUID.randomUUID();
        McpTransportContext ctx = context(new McpPrincipal(42L, List.of("mcp.case")), idemKey);
        Map<String, Object> args = Map.of("project_id", 3, "title", "登录校验", "steps", "输入密码", "expected", "通过");
        McpSchema.CallToolResult first = toolResult(callTool(ctx, "create_case", args));
        assertFalse(Boolean.TRUE.equals(first.isError()));
        assertTrue(jsonMapper.writeValueAsString(first.structuredContent()).contains("caseId"), "结构化结果透传");
        assertEquals("create_case", gateway.lastTool);
        assertEquals(42L, gateway.lastUserId, "属主 userId 透传（审计归属）");
        assertEquals(3, gateway.lastArguments.get("project_id"));
        try {
            McpTransportContext replayCtx = context(new McpPrincipal(42L, List.of("mcp.case")), idemKey);
            McpSchema.CallToolResult replay = toolResult(callTool(replayCtx, "create_case", args));
            assertFalse(Boolean.TRUE.equals(replay.isError()));
            assertEquals(1, gateway.calls, "幂等回放不得二次执行（TC-MCP-04 不双建）");
            assertNotNull(replay.structuredContent(), "回放须携带原结果");
        } finally {
            redis.delete("idempotent:" + McpServerHandler.idempotentKeyForTest("create_case", idemKey));
        }
    }

    @Test
    void tools_call_delete_case_走写件幂等径生成审批单() {
        String idemKey = "k-" + UUID.randomUUID();
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case")), idemKey);
        Map<String, Object> args = Map.of("project_id", 1, "case_id", 8, "reason", "重复用例");
        try {
            McpSchema.CallToolResult result = toolResult(callTool(ctx, "delete_case", args));
            assertFalse(Boolean.TRUE.equals(result.isError()), "接通后成功径非 isError");
            assertEquals(1, gateway.calls, "delete_case 经网关触达 core 审批单面");
            assertEquals("delete_case", gateway.lastTool);
        } finally {
            redis.delete("idempotent:" + McpServerHandler.idempotentKeyForTest("delete_case", idemKey));
        }
    }

    @Test
    void tools_call_只读开关下写件调用仍拒_防旧缓存直调() {
        readonly.set(true);
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case")), "k-" + UUID.randomUUID());
        assertRpcError(callTool(ctx, "tag_case", Map.of("project_id", 1, "case_id", 2, "tags", List.of("冒烟"))),
                McpSchema.ErrorCodes.METHOD_NOT_FOUND, "not found");
        assertEquals(0, gateway.calls);
    }

    @Test
    void tools_call_网关异常_isError人话不炸协议() {
        gateway.failure = new IllegalStateException("core 不可用");
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case")), "k-" + UUID.randomUUID());
        Map<String, Object> args = Map.of("project_id", 1, "title", "t", "steps", "s", "expected", "e");
        McpSchema.CallToolResult result = toolResult(callTool(ctx, "create_case", args));
        assertTrue(Boolean.TRUE.equals(result.isError()), "执行失败=isError 人话（MCP 语义），禁协议层炸 500");
        assertTrue(((McpSchema.TextContent) result.content().get(0)).text().contains("core 不可用"),
                "人话透传执行面错误");
    }


    @Test
    void tools_call_成功_留痕caller_mcp_属主_入参摘要() {
        McpTransportContext ctx = context(new McpPrincipal(42L, List.of("mcp.case")), "k-" + UUID.randomUUID());
        Map<String, Object> args = Map.of("project_id", 1, "title", "t", "steps", "s", "expected", "e");
        callTool(ctx, "create_case", args);

        assertEquals(1, records.all.size(), "写工具成功须留痕恰 1 条");
        var rec = records.all.get(0);
        assertEquals("mcp", rec.caller());
        assertEquals(42L, rec.actorId(), "actor=Token 属主（V42 裁定④）");
        assertEquals("create_case", rec.toolName());
        assertEquals(1, rec.success());
        assertTrue(rec.durationMs() != null && rec.durationMs() >= 0);
        assertNull(rec.error());
        assertTrue(rec.digest().contains("project_id"), "digest=入参 JSON 原文（core SHA-256 归一）");
    }

    @Test
    void tools_call_网关异常_留痕success_0_含人话error() {
        gateway.failure = new IllegalStateException("core 不可用");
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.read")), null);
        callTool(ctx, "get_cases", Map.of("project_id", 1));

        assertEquals(1, records.all.size(), "失败调用同样留痕（审计不挑成败）");
        var rec = records.all.get(0);
        assertEquals(0, rec.success());
        assertTrue(rec.error().contains("core 不可用"), rec.error());
    }

    @Test
    void tools_call_写工具网关业务异常_同样留痕success_0_L4实证缺口() {
        gateway.failure = new com.sixbean.testpike.common.exception.ServiceException(
                "标签「xyz」不在项目标签字典中",
                com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
        McpTransportContext ctx = context(new McpPrincipal(7L, List.of("mcp.case")),
                "k-" + UUID.randomUUID());
        callTool(ctx, "tag_case", Map.of("project_id", 1, "case_id", 2, "tags", List.of("xyz")));

        assertEquals(1, records.all.size(), "写工具失败调用同样留痕（ServiceException 分支）");
        var rec = records.all.get(0);
        assertEquals("mcp", rec.caller());
        assertEquals(0, rec.success());
        assertTrue(rec.error().contains("不在项目标签字典中"), rec.error());
        assertTrue(rec.digest().contains("case_id"), "digest 仍含入参");
    }

    @Test
    void tools_call_幂等回放_同样留痕_网关仍恰1次() {
        String key = "k-" + UUID.randomUUID();
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case")), key);
        Map<String, Object> args = Map.of("project_id", 1, "title", "t", "steps", "s", "expected", "e");
        callTool(ctx, "create_case", args);
        callTool(ctx, "create_case", args);
        assertEquals(1, gateway.calls, "回放不得二次执行（TC-MCP-04 既有断言面回归）");
        assertEquals(2, records.all.size(), "调用级审计：回放也是对客一次成功调用，须留痕");
    }

    @Test
    void tools_call_拒止路径_零留痕_与零外呼同口径() {
        McpTransportContext ctx = context(new McpPrincipal(9L, List.of("mcp.case")), null);
        callTool(ctx, "create_case", Map.of("project_id", 1, "title", "t", "steps", "s", "expected", "e"));
        callTool(ctx, "no_such_tool", Map.of());
        callTool(ctx, "delete_case", Map.of("case_id", 1));
        assertEquals(0, gateway.calls);
        assertEquals(0, records.all.size(), "未达执行面的拒止零留痕（留痕=调用事实源，拒止由协议错误承载）");
    }

    @Test
    void notification_静默吞_未知方法_32601() {
        assertNull(handler.handleNotification(context(null, null),
                new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
                        McpSchema.METHOD_NOTIFICATION_INITIALIZED, Map.of())).block());
        assertRpcError(call(context(new McpPrincipal(9L, List.of()), null), "resources/list", Map.of()),
                McpSchema.ErrorCodes.METHOD_NOT_FOUND, "resources/list");
    }
}
