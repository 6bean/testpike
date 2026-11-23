package com.sixbean.testpike.core.mcp.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


@Component
public class McpHandshakeClient {

    
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    
    static final String PROTOCOL_VERSION = "2025-03-26";
    private static final String CLIENT_NAME = "testpike-core";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong requestId = new AtomicLong(1);

    
    public McpHandshakeOutcome probe(String endpoint, String authPlain) {
        String sessionId = null;
        String serverName = null;
        // ① initialize
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", PROTOCOL_VERSION);
        initParams.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = initParams.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", "1.0");
        ObjectNode init = rpcRequest("initialize", initParams);
        JsonNode initResult;
        try {
            HttpResponse<String> resp = post(endpoint, authPlain, init, null);
            if (isAuthFailure(resp.statusCode())) {
                return McpHandshakeOutcome.fail(McpProbeCategory.AUTH,
                        "端点拒绝鉴权（HTTP " + resp.statusCode() + "），请检查访问凭证配置");
            }
            McpHandshakeOutcome malformed = malformedIfBadStatus(resp);
            if (malformed != null) {
                return malformed;
            }
            McpHandshakeOutcome err = jsonRpcErrorOf(resp.body());
            if (err != null) {
                return err;
            }
            JsonNode root = readJson(resp.body());
            initResult = root == null ? null : root.get("result");
            if (initResult == null || !initResult.isObject()) {
                return McpHandshakeOutcome.fail(McpProbeCategory.PROTOCOL,
                        "握手响应非法：initialize 缺少 result（疑似非 MCP 服务端）");
            }
            serverName = initResult.path("serverInfo").path("name").asText(null);
            sessionId = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
        } catch (HttpTimeoutException e) {
            return timeoutFailure("initialize");
        } catch (IOException e) {
            return networkFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpHandshakeOutcome.fail(McpProbeCategory.NETWORK, "握手被中断");
        }

        try {
            post(endpoint, authPlain, rpcNotification("notifications/initialized"), sessionId);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            HttpResponse<String> resp = post(endpoint, authPlain,
                    rpcRequest("tools/list", objectMapper.createObjectNode()), sessionId);
            if (isAuthFailure(resp.statusCode())) {
                return McpHandshakeOutcome.fail(McpProbeCategory.AUTH,
                        "端点拒绝鉴权（HTTP " + resp.statusCode() + "），请检查访问凭证配置");
            }
            McpHandshakeOutcome malformed = malformedIfBadStatus(resp);
            if (malformed != null) {
                return malformed;
            }
            McpHandshakeOutcome err = jsonRpcErrorOf(resp.body());
            if (err != null) {
                return err;
            }
            JsonNode root = readJson(resp.body());
            JsonNode tools = root == null ? null : root.path("result").get("tools");
            if (tools == null || !tools.isArray()) {
                return McpHandshakeOutcome.fail(McpProbeCategory.PROTOCOL,
                        "握手响应非法：tools/list 缺少 result.tools 数组");
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonNode t : tools) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", t.path("name").asText(""));
                tool.put("description", t.path("description").asText(""));
                tool.put("inputSchemaDigest", digest(t.get("inputSchema")));
                // Standard MCP tool annotations (readOnlyHint/destructiveHint/...) must
                // survive the handshake: downstream guards such as the knowledge-sync
                // read-only whitelist fail closed when the annotation is absent, so
                // dropping this block would silently reject every subscription.
                JsonNode annotations = t.get("annotations");
                if (annotations != null && annotations.isObject()) {
                    tool.put("annotations", annotations);
                }
                list.add(tool);
            }
            return McpHandshakeOutcome.ok(list, serverName);
        } catch (HttpTimeoutException e) {
            return timeoutFailure("tools/list");
        } catch (IOException e) {
            return networkFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpHandshakeOutcome.fail(McpProbeCategory.NETWORK, "握手被中断");
        }
    }

    
    private String digest(JsonNode inputSchema) {
        try {
            String canon = objectMapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(inputSchema == null
                            ? objectMapper.createObjectNode() : inputSchema);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    md.digest(canon.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (Exception e) {
            return "noschema";
        }
    }

    private HttpResponse<String> post(String endpoint, String authPlain,
                                      ObjectNode body, String sessionId, Duration budget)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(budget)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (authPlain != null && !authPlain.isBlank()) {
            String value = authPlain.startsWith("Bearer ") || authPlain.startsWith("bearer ")
                    ? authPlain : "Bearer " + authPlain;
            builder.header("Authorization", value);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode rpcRequest(String method, JsonNode params) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", requestId.getAndIncrement());
        req.put("method", method);
        req.set("params", params);
        return req;
    }

    private ObjectNode rpcNotification(String method) {
        ObjectNode req = objectMapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        return req;
    }

    
    public McpToolCallOutcome callTool(String endpoint, String authPlain, String toolName,
                                       JsonNode arguments, Duration callBudget) {
        String sessionId = null;
        // ① initialize
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("protocolVersion", PROTOCOL_VERSION);
        initParams.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = initParams.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", "1.0");
        try {
            HttpResponse<String> init = post(endpoint, authPlain,
                    rpcRequest("initialize", initParams), null, callBudget);
            McpToolCallOutcome bad = classifyHttp(init, "initialize");
            if (bad != null) {
                return bad;
            }
            JsonNode initRoot = readJson(init.body());
            if (initRoot == null || !initRoot.path("result").isObject()) {
                return McpToolCallOutcome.fail("protocol",
                        "MCP 握手响应非法（initialize 缺少 result），疑似非 MCP 服务端");
            }
            sessionId = init.headers().firstValue("Mcp-Session-Id").orElse(null);
        } catch (HttpTimeoutException e) {
            return McpToolCallOutcome.fail("timeout", "MCP 握手超时（"
                    + callBudget.toMillis() + " 毫秒预算内未响应，阶段=initialize）");
        } catch (IOException e) {
            return callNetworkFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpToolCallOutcome.fail("network", "MCP 转发被中断");
        }
        try {
            post(endpoint, authPlain, rpcNotification("notifications/initialized"), sessionId, callBudget);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        // ③ tools/call
        ObjectNode callParams = objectMapper.createObjectNode();
        callParams.put("name", toolName);
        callParams.set("arguments", arguments);
        try {
            HttpResponse<String> resp = post(endpoint, authPlain,
                    rpcRequest("tools/call", callParams), sessionId, callBudget);
            McpToolCallOutcome bad = classifyHttp(resp, "tools/call");
            if (bad != null) {
                return bad;
            }
            JsonNode root = readJson(resp.body());
            if (root == null) {
                return McpToolCallOutcome.fail("protocol",
                        "MCP 端点返回非法 JSON-RPC 报文（无法解析），疑似非 MCP 服务端");
            }
            JsonNode error = root.get("error");
            if (error != null && error.isObject()) {
                return McpToolCallOutcome.fail("protocol", "MCP 调用被服务端拒绝（协议异常）："
                        + error.path("message").asText("无描述"));
            }
            JsonNode result = root.get("result");
            if (result == null || !result.isObject()) {
                return McpToolCallOutcome.fail("protocol",
                        "MCP 调用响应非法（tools/call 缺少 result）");
            }
            String text = textOf(result.path("content"));
            if (result.path("isError").asBoolean(false)) {
                return McpToolCallOutcome.fail("tool", "MCP 工具执行返回错误：" + text);
            }
            return McpToolCallOutcome.ok(text);
        } catch (HttpTimeoutException e) {
            return McpToolCallOutcome.fail("timeout", "MCP 工具调用超时（"
                    + callBudget.toMillis() + " 毫秒预算内未响应，阶段=tools/call）");
        } catch (IOException e) {
            return callNetworkFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpToolCallOutcome.fail("network", "MCP 转发被中断");
        }
    }

    
    private static String textOf(JsonNode content) {
        if (content == null || !content.isArray() || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if ("text".equals(c.path("type").asText())) {
                sb.append(c.path("text").asText(""));
            } else {
                sb.append('[').append(c.path("type").asText("unknown")).append(" 内容]");
            }
        }
        return sb.toString();
    }

    
    private static McpToolCallOutcome classifyHttp(HttpResponse<String> resp, String phase) {
        int code = resp.statusCode();
        if (isAuthFailure(code)) {
            return McpToolCallOutcome.fail("auth",
                    "MCP 端点拒绝鉴权（HTTP " + code + "，阶段=" + phase + "），请检查该服务的访问凭证配置");
        }
        if (code >= 400) {
            return McpToolCallOutcome.fail("http", "MCP 端点返回 HTTP " + code
                    + "（非合法 MCP 响应，阶段=" + phase + "）");
        }
        return null;
    }

    private static McpToolCallOutcome callNetworkFailure(IOException e) {
        return McpToolCallOutcome.fail("network",
                "无法连接到 MCP 端点（" + McpNetworkFailureText.describe(e) + "）");
    }

    
    private HttpResponse<String> post(String endpoint, String authPlain,
                                      ObjectNode body, String sessionId)
            throws IOException, InterruptedException {
        return post(endpoint, authPlain, body, sessionId, REQUEST_TIMEOUT);
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    
    private McpHandshakeOutcome jsonRpcErrorOf(String body) {
        JsonNode root = readJson(body);
        if (root == null) {
            return McpHandshakeOutcome.fail(McpProbeCategory.PROTOCOL,
                    "端点返回非法 JSON-RPC 报文（无法解析），疑似非 MCP 服务端");
        }
        JsonNode error = root.get("error");
        if (error != null && error.isObject()) {
            return McpHandshakeOutcome.fail(McpProbeCategory.PROTOCOL,
                    "握手被服务端拒绝（协议异常）：" + error.path("message").asText("无描述"));
        }
        return null;
    }

    private McpHandshakeOutcome malformedIfBadStatus(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code >= 400) {
            return McpHandshakeOutcome.fail(McpProbeCategory.PROTOCOL,
                    "端点返回 HTTP " + code + "（非合法 MCP 响应）");
        }
        return null;
    }

    private static boolean isAuthFailure(int status) {
        return status == HttpURLConnection.HTTP_UNAUTHORIZED
                || status == HttpURLConnection.HTTP_FORBIDDEN;
    }

    private static McpHandshakeOutcome timeoutFailure(String phase) {
        return McpHandshakeOutcome.fail(McpProbeCategory.PROTOCOL,
                "握手超时（" + REQUEST_TIMEOUT.toSeconds() + " 秒预算内未响应，阶段=" + phase + "）");
    }

    private static McpHandshakeOutcome networkFailure(IOException e) {
        return McpHandshakeOutcome.fail(McpProbeCategory.NETWORK,
                "无法连接到 MCP 端点（" + McpNetworkFailureText.describe(e) + "）");
    }
}
