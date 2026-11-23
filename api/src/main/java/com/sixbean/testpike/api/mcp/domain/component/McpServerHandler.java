package com.sixbean.testpike.api.mcp.domain.component;

import com.sixbean.testpike.api.mcp.infrastructure.remote.McpInvocationRemoteInput;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpCaller;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpToolGateway;
import com.sixbean.testpike.api.mcp.infrastructure.remote.ToolInvocationRecordClient;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;


public class McpServerHandler implements McpStatelessServerHandler {

    private static final Logger log = LoggerFactory.getLogger(McpServerHandler.class);

    
    private static final String JOB_PREFIX = "mcp:";

 
    private static final String TOOL_NOT_FOUND = "Tool not found（工具不存在或当前 Token 无权限）";

    private final McpToolCatalog catalog;
    private final McpToolVisibilityFilter visibilityFilter;
    private final McpPassThroughToolRegistry passThroughRegistry;
    private final McpJsonMapper jsonMapper;
    private final JsonSchemaValidator schemaValidator;
    private final BooleanSupplier readonlySupplier;
    private final McpToolGateway gateway;
    private final IdempotentGuard idempotentGuard;
    private final ToolInvocationRecordClient recordClient;

    public McpServerHandler(McpToolCatalog catalog, McpToolVisibilityFilter visibilityFilter,
                            McpPassThroughToolRegistry passThroughRegistry,
                            McpJsonMapper jsonMapper, JsonSchemaValidator schemaValidator,
                            BooleanSupplier readonlySupplier, McpToolGateway gateway,
                            IdempotentGuard idempotentGuard,
                            ToolInvocationRecordClient recordClient) {
        this.catalog = catalog;
        this.visibilityFilter = visibilityFilter;
        this.passThroughRegistry = passThroughRegistry;
        this.jsonMapper = jsonMapper;
        this.schemaValidator = schemaValidator;
        this.readonlySupplier = readonlySupplier;
        this.gateway = gateway;
        this.idempotentGuard = idempotentGuard;
        this.recordClient = recordClient;
    }

    @Override
    public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext transportContext,
                                                         McpSchema.JSONRPCRequest request) {
        return switch (request.method()) {
            case McpSchema.METHOD_INITIALIZE -> Mono.just(success(request, initializeResult()));
            case McpSchema.METHOD_PING -> Mono.just(success(request, Map.of()));
            case McpSchema.METHOD_TOOLS_LIST -> handleToolsList(request, transportContext);
            case McpSchema.METHOD_TOOLS_CALL -> handleToolCall(request, transportContext);
            default -> Mono.just(error(request, McpSchema.ErrorCodes.METHOD_NOT_FOUND,
                    "Unsupported method: " + request.method()));
        };
    }

    @Override
    public Mono<Void> handleNotification(McpTransportContext transportContext,
                                         McpSchema.JSONRPCNotification notification) {
        return Mono.empty();
    }

    // ===== initialize =====

    private McpSchema.InitializeResult initializeResult() {
        return new McpSchema.InitializeResult(
                McpSchema.LATEST_PROTOCOL_VERSION,
                McpSchema.ServerCapabilities.builder().tools(true).build(),
                new McpSchema.Implementation("testpike", "1.0.0"),
                "testPike 智能生态 MCP 服务：建用例（落待评审）、删除（走审批单）、打标与"
                        + "需求/用例/脚本/执行结果只读查询，外加 Mock 自助面（查空间/检视命中只读，"
                        + "建规则/发布空间须 mcp.mock）。全部操作按 API Token 的项目 RBAC 隔离，"
                        + "写操作必须携带幂等键：工具参数 idempotency_key（客户端无法自定义请求头时用它），"
                        + "等价通道=HTTP 头 Idempotency-Key。");
    }


    private Mono<McpSchema.JSONRPCResponse> handleToolsList(McpSchema.JSONRPCRequest request,
                                                            McpTransportContext ctx) {
        Optional<McpPrincipal> principal = principalOf(ctx);
        if (principal.isEmpty()) {
            return Mono.just(unauthenticated(request));
        }
        List<McpSchema.Tool> tools = visibleDefinitions(principal.get()).stream()
                .map(this::toSchemaTool)
                .toList();
        return Mono.just(success(request, new McpSchema.ListToolsResult(tools, null)));
    }

    private List<McpToolDefinition> visibleDefinitions(McpPrincipal principal) {
        // Static registry plus the catalog-derived family, both through the same filter: a scope that
        // cannot see reads sees neither, and a read-only token sees no writes in either list.
        List<McpToolDefinition> dynamic = passThroughRegistry.tools().stream()
                .map(McpPassThroughToolRegistry.PassThroughTool::definition)
                .toList();
        return visibilityFilter.visibleFor(principal.scopes(), readonlySupplier.getAsBoolean(), dynamic);
    }

    /** Name resolution across both sources (the registry first: its names are generated). */
    private Optional<McpToolDefinition> resolveDefinition(String name) {
        return passThroughRegistry.byName(name)
                .map(McpPassThroughToolRegistry.PassThroughTool::definition)
                .or(() -> catalog.byName(name));
    }

    private McpSchema.Tool toSchemaTool(McpToolDefinition definition) {
        return McpSchema.Tool.builder()
                .name(definition.name())
                .description(definition.description())
                .inputSchema(jsonMapper, advertiseIdempotencyArgument(definition))
                .build();
    }

    /**
     * Write tools publish the argument channel for their idempotency key next to the header channel:
     * a client must be able to satisfy the guard from the schema it was handed, otherwise the tools
     * it cannot set headers for get a refusal it cannot act on. Read tools are left untouched.
     */
    @SuppressWarnings("unchecked")
    private String advertiseIdempotencyArgument(McpToolDefinition definition) {
        if (!definition.writeTool()) {
            return definition.inputSchemaJson();
        }
        try {
            Map<String, Object> schema = jsonMapper.readValue(definition.inputSchemaJson(), Map.class);
            if (!(schema.get("properties") instanceof Map<?, ?>)) {
                return definition.inputSchemaJson();
            }
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            properties.putIfAbsent(McpIdempotencyKey.ARGUMENT, Map.of(
                    "type", "string",
                    "description", "写操作幂等键：一次操作一个值（建议随机串），同键重复调用返回首次结果、"
                            + "不重复执行；无法自定义请求头的客户端用本参数即可。"));
            return jsonMapper.writeValueAsString(schema);
        } catch (IOException | RuntimeException ex) {
            // A malformed catalog schema must not take the whole tool list down with it.
            log.warn("MCP 写工具幂等参数补挂失败 tool={} err={}", definition.name(), ex.getMessage());
            return definition.inputSchemaJson();
        }
    }


    private Mono<McpSchema.JSONRPCResponse> handleToolCall(McpSchema.JSONRPCRequest request,
                                                           McpTransportContext ctx) {
        Optional<McpPrincipal> principal = principalOf(ctx);
        if (principal.isEmpty()) {
            return Mono.just(unauthenticated(request));
        }
        McpSchema.CallToolRequest call = jsonMapper.convertValue(request.params(), McpSchema.CallToolRequest.class);
        Set<String> visible = visibleDefinitions(principal.get()).stream()
                .map(McpToolDefinition::name).collect(Collectors.toSet());
        Optional<McpToolDefinition> definition = resolveDefinition(call.name())
                .filter(d -> visible.contains(d.name()));
        if (definition.isEmpty()) {
            return Mono.just(error(request, McpSchema.ErrorCodes.METHOD_NOT_FOUND, TOOL_NOT_FOUND));
        }
        McpToolDefinition tool = definition.get();
        Map<String, Object> supplied = call.arguments() == null ? Map.of() : call.arguments();
        // The idempotency key travels either as the transport header or inside the argument bag: a
        // generic MCP bridge cannot attach per-request headers, so a header-only guard made every
        // write tool unreachable from an agent conversation. Only the channel widens — a keyless
        // write is still refused before the gateway, and the forwarded/audited argument bag keeps
        // nothing but the tool-declared business arguments.
        String idempotencyKey = McpIdempotencyKey.resolve(ctx, supplied);
        Map<String, Object> arguments = McpIdempotencyKey.withoutTransportKeys(supplied);

        String schemaError = validateArguments(tool, arguments);
        if (schemaError != null) {
            return Mono.just(error(request, McpSchema.ErrorCodes.INVALID_PARAMS,
                    "参数不符合工具 input schema：" + schemaError));
        }


        long userId = principal.get().userId();
        Long tokenId = principal.get().tokenId();
        // GA1: owner id + token project pin travel together to the gateway gates.
        McpCaller caller = new McpCaller(userId, principal.get().projectScope());
        Optional<McpPassThroughToolRegistry.PassThroughTool> passThrough =
                passThroughRegistry.byName(tool.name());
        if (passThrough.isPresent()) {
            // Catalog-derived read: the door in core decides membership, budget and width, so this
            // face adds no authority of its own — and it can never route a change, because the
            // projection only contains entries the read door already allows.
            long start = System.nanoTime();
            try {
                String json = gateway.invokePassThrough(passThrough.get().endpointKey(),
                        arguments, userId);
                recordCall(userId, tokenId, tool.name(), arguments, start, null);
                return Mono.just(success(request, toolResult(toContentMap(json))));
            } catch (RuntimeException ex) {
                log.warn("MCP 直通只读工具执行失败 tool={} err={}", tool.name(), ex.getMessage());
                recordCall(userId, tokenId, tool.name(), arguments, start, ex.getMessage());
                return Mono.just(success(request, errorResult(ex.getMessage())));
            }
        }
        if (McpToolCatalog.TOOL_TOKENS_USAGE.equals(tool.name())) {
            // Usage numbers describe the token row that presented this very call:
            // nothing here can be pointed at somebody else's ledger, and ownership
            // is re-checked once inside core (S2b door), never twice on this face.
            if (tokenId == null) {
                return Mono.just(error(request, McpSchema.ErrorCodes.INVALID_PARAMS,
                        "tokens.usage 汇报的是本次调用所出示 API Token 的用量账，当前认证结果未关联到"
                                + "令牌行，无法给出数字——请确认出示的凭据是平台创建的 API Token"));
            }
            long start = System.nanoTime();
            try {
                String json = gateway.tokenUsage(tokenId, userId);
                recordCall(userId, tokenId, tool.name(), arguments, start, null);
                return Mono.just(success(request, toolResult(toContentMap(json))));
            } catch (RuntimeException ex) {
                log.warn("MCP 令牌用量读件执行失败 tool={} err={}", tool.name(), ex.getMessage());
                recordCall(userId, tokenId, tool.name(), arguments, start, ex.getMessage());
                return Mono.just(success(request, errorResult(ex.getMessage())));
            }
        }
        if (tool.writeTool()) {
            if (idempotencyKey == null) {
                return Mono.just(error(request, McpSchema.ErrorCodes.INVALID_PARAMS,
                        McpIdempotencyKey.missingKeyMessage()));
            }
            long start = System.nanoTime();
            try {
                Map<String, Object> result = idempotentGuard.execute(
                        JOB_PREFIX + tool.name() + ":" + idempotencyKey,
                        () -> toContentMap(gateway.invoke(tool.name(), arguments, caller)));
                recordCall(userId, tokenId, tool.name(), arguments, start, null);
                return Mono.just(success(request, toolResult(result)));
            } catch (ServiceException ex) {
                log.warn("MCP 写工具执行失败 tool={} err={}", tool.name(), ex.getMessage());
                recordCall(userId, tokenId, tool.name(), arguments, start, ex.getMessage());
                return Mono.just(success(request, errorResult(ex.getMessage())));
            } catch (RuntimeException ex) {
                log.warn("MCP 写工具执行失败 tool={} err={}", tool.name(), ex.getMessage());
                recordCall(userId, tokenId, tool.name(), arguments, start, ex.getMessage());
                return Mono.just(success(request, errorResult(ex.getMessage())));
            }
        }
        long start = System.nanoTime();
        try {
            String json = gateway.invoke(tool.name(), arguments, caller);
            recordCall(userId, tokenId, tool.name(), arguments, start, null);
            return Mono.just(success(request, toolResult(toContentMap(json))));
        } catch (RuntimeException ex) {
            log.warn("MCP 只读工具执行失败 tool={} err={}", tool.name(), ex.getMessage());
            recordCall(userId, tokenId, tool.name(), arguments, start, ex.getMessage());
            return Mono.just(success(request, errorResult(ex.getMessage())));
        }
    }


    
    private void recordCall(long actorId, Long apiTokenId, String toolName,
                            Map<String, Object> arguments, long startNanos, String error) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        String digest;
        try {
            digest = jsonMapper.writeValueAsString(arguments);
        } catch (Exception ex) {
            digest = String.valueOf(arguments);
        }
        McpInvocationRemoteInput row = error == null
                ? McpInvocationRemoteInput.ok(actorId, apiTokenId, toolName, durationMs, digest)
                : McpInvocationRemoteInput.fail(actorId, apiTokenId, toolName, durationMs, error, digest);
        recordClient.record(List.of(row));
    }


    private String validateArguments(McpToolDefinition tool, Map<String, Object> arguments) {
        JsonSchemaValidator.ValidationResponse response =
                schemaValidator.validate(parseSchemaMap(tool.inputSchemaJson()), arguments);
        return response.valid() ? null : response.errorMessage();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchemaMap(String schemaJson) {
        try {
            return jsonMapper.readValue(schemaJson, Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException("工具 input schema 解析失败（目录件自校验漏网，理论不可达）", ex);
        }
    }


    
    @SuppressWarnings("unchecked")
    private Map<String, Object> toContentMap(String json) {
        try {
            Object parsed = jsonMapper.readValue(json, Object.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of("result", parsed);
        } catch (Exception ex) {
            return Map.of("raw", json == null ? "" : json);
        }
    }

    private McpSchema.CallToolResult toolResult(Map<String, Object> structured) {
        try {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(jsonMapper.writeValueAsString(structured))),
                    false, structured);
        } catch (Exception ex) {
            return new McpSchema.CallToolResult(String.valueOf(structured), false);
        }
    }

    private McpSchema.CallToolResult errorResult(String message) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("执行失败：" + (message == null ? "未知错误" : message))), true);
    }


    private static Optional<McpPrincipal> principalOf(McpTransportContext ctx) {
        Object value = ctx == null ? null : ctx.get(McpContextKeys.PRINCIPAL);
        return value instanceof McpPrincipal principal ? Optional.of(principal) : Optional.empty();
    }

    private static McpSchema.JSONRPCResponse unauthenticated(McpSchema.JSONRPCRequest request) {
        return error(request, McpSchema.ErrorCodes.INVALID_REQUEST,
                "缺少认证上下文（请经 /mcp 端点携带 Bearer API Token 调用）");
    }

    private static McpSchema.JSONRPCResponse success(McpSchema.JSONRPCRequest request, Object result) {
        return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), result, null);
    }

    private static McpSchema.JSONRPCResponse error(McpSchema.JSONRPCRequest request, int code, String message) {
        return new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(), null,
                new McpSchema.JSONRPCResponse.JSONRPCError(code, message, null));
    }

    
    static String idempotentKeyForTest(String toolName, String idempotencyKey) {
        return JOB_PREFIX + toolName + ":" + idempotencyKey;
    }
}
