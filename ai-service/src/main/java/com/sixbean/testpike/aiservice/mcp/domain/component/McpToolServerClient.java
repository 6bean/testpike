package com.sixbean.testpike.aiservice.mcp.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


public class McpToolServerClient implements McpToolSession {

    
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    static final String PROTOCOL_VERSION = "2025-03-26";

    private final McpAssemblyRemoteServer server;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestId = new AtomicLong(1);
    private final Map<String, JsonNode> schemasByName = new LinkedHashMap<>();
    private volatile String sessionId;

    McpToolServerClient(McpAssemblyRemoteServer server, HttpClient httpClient,
                        ObjectMapper objectMapper) {
        this.server = server;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpAssemblyRemoteServer server() {
        return server;
    }

    
    @Override
    public List<String> allowedTools() {
        return server.toolNames() == null ? List.of() : server.toolNames();
    }

    
    public String connect() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "testpike-ai-service");
        clientInfo.put("version", "1.0");
        JsonNode init;
        try {
            init = rpc("initialize", params);
        } catch (McpCallFailedException e) {
            return e.getMessage();
        }
        if (init == null) {
            return "握手响应非法（initialize 缺少 result），疑似非 MCP 服务端";
        }
        try {
            notify("notifications/initialized");
        } catch (McpCallFailedException e) {
        }
        try {
            JsonNode tools = rpc("tools/list", objectMapper.createObjectNode());
            if (tools != null && tools.isArray()) {
                for (JsonNode t : tools) {
                    schemasByName.put(t.path("name").asText(""), t.path("inputSchema"));
                }
            }
        } catch (McpCallFailedException e) {
        }
        return null;
    }

    
    public McpToolCallResult call(String toolName, String query) {
        if (!allowedTools().contains(toolName)) {
            return rejectOutsideAllowlist(toolName);
        }
        return execute(toolName, buildArgs(toolName, query), null);
    }

    
    @Override
    public McpToolCallResult callWithArguments(String toolName, String argumentsJson) {
        if (!allowedTools().contains(toolName)) {
            return rejectOutsideAllowlist(toolName);
        }
        JsonNode args;
        try {
            args = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank()
                    ? "{}" : argumentsJson);
        } catch (Exception e) {
            return McpToolCallResult.fail(toolName, server.serverId(),
                    "参数体不是合法 JSON（已拒绝执行）", 0L, null);
        }
        if (!args.isObject()) {
            return McpToolCallResult.fail(toolName, server.serverId(),
                    "参数体不是 JSON 对象（已拒绝执行）", 0L, null);
        }
        return execute(toolName, (ObjectNode) args, argumentsJson);
    }

    private McpToolCallResult rejectOutsideAllowlist(String toolName) {
        return McpToolCallResult.fail(toolName, server.serverId(),
                "工具未在白名单勾选集内（已拒绝）", 0L, null);
    }

    
    private McpToolCallResult execute(String toolName, ObjectNode args, String digestOverride) {
        long start = System.nanoTime();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", args);
        String digest = digestOverride != null ? digestOverride : args.toString();
        try {
            JsonNode result = rpc("tools/call", params);
            long ms = elapsedMs(start);
            if (result == null) {
                return McpToolCallResult.fail(toolName, server.serverId(),
                        "工具响应非法（缺少 result）", ms, digest);
            }
            if (result.path("isError").asBoolean(false)) {
                return McpToolCallResult.fail(toolName, server.serverId(),
                        "工具执行返回错误：" + textOf(result.path("content")), ms, digest);
            }
            return McpToolCallResult.ok(toolName, server.serverId(),
                    textOf(result.path("content")), ms, digest);
        } catch (McpCallFailedException e) {
            return McpToolCallResult.fail(toolName, server.serverId(), e.getMessage(),
                    elapsedMs(start), digest);
        }
    }

    
    private ObjectNode buildArgs(String toolName, String query) {
        ObjectNode args = objectMapper.createObjectNode();
        JsonNode props = schemasByName.getOrDefault(toolName,
                objectMapper.createObjectNode()).path("properties");
        if (!props.isObject() || query == null || query.isBlank()) {
            return args;
        }
        List<String> names = new ArrayList<>();
        props.fieldNames().forEachRemaining(names::add);
        String hit = null;
        for (String name : names) {
            String lower = name.toLowerCase();
            if (lower.equals("q") || lower.equals("query") || lower.equals("keyword")
                    || lower.equals("text") || lower.equals("input") || lower.equals("search")) {
                hit = name;
                break;
            }
        }
        if (hit == null && names.size() == 1
                && "string".equals(props.path(names.get(0)).path("type").asText())) {
            hit = names.get(0);
        }
        if (hit != null) {
            args.put(hit, query);
        }
        return args;
    }

    private String textOf(JsonNode content) {
        if (content == null || !content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText())) {
                sb.append(item.path("text").asText("")).append('\n');
            }
        }
        return sb.toString().strip();
    }

    private JsonNode rpc(String method, JsonNode params) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", requestId.getAndIncrement());
        req.put("method", method);
        req.set("params", params);
        JsonNode root = post(req.toString());
        if (root.hasNonNull("error")) {
            throw new McpCallFailedException("工具端拒绝："
                    + root.path("error").path("message").asText("未知"));
        }
        return root.has("result") ? root.get("result") : null;
    }

    private void notify(String method) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        post(req.toString());
    }

    private JsonNode post(String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.endpoint()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        String auth = server.auth();
        if (auth != null && !auth.isBlank()) {
            builder.header("Authorization",
                    auth.startsWith("Bearer ") || auth.startsWith("bearer ") ? auth : "Bearer " + auth);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new McpCallFailedException("无法连接 MCP 服务端（" + server.name() + "）："
                    + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpCallFailedException("调用被中断");
        }
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            throw new McpCallFailedException("端点拒绝鉴权（HTTP " + resp.statusCode() + "）");
        }
        if (resp.statusCode() >= 400) {
            throw new McpCallFailedException("端点返回 HTTP " + resp.statusCode());
        }
        resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> this.sessionId = s);
        try {
            return objectMapper.readTree(resp.body());
        } catch (IOException e) {
            throw new McpCallFailedException("端点返回非法 JSON-RPC 报文（无法解析）");
        }
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}
