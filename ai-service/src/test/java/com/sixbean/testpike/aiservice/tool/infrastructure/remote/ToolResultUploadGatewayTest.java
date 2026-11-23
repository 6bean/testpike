package com.sixbean.testpike.aiservice.tool.infrastructure.remote;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-result upload gateway unit test . L2 isolates
 * infrastructure with a JDK HttpServer stub: the core grant endpoint answers
 * with a Response-wrapped brief whose uploadUrl points at a /put-echo sink.
 * Pins, in order: the GET path and URL-encoded fileName contract towards
 * /internal/ai-chat/tool-artifact/grant; the raw UTF-8 PUT of the full text
 * with a text/plain content type; the returned anchor being the objectKey;
 * the task-scope file name (task{id}-...) versus the dialog-scope one
 * (dialog-...) that the retrieval meta-tool later resolves ownership from;
 * and that a refused grant propagates instead of being swallowed into a
 * "stored" lie (the budget folder owns the only fail-soft point).
 */
class ToolResultUploadGatewayTest {

    private static final String STUB_KEY =
            "testpike/0/tool-result/202611/abcd1234-task42-x.txt";

    private static HttpServer server;
    private static final AtomicBoolean GRANT_REFUSED = new AtomicBoolean(false);
    private static final List<String> GRANT_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> PUT_METHODS = new CopyOnWriteArrayList<>();
    private static final List<String> PUT_CONTENT_TYPES = new CopyOnWriteArrayList<>();
    private static final List<byte[]> PUT_BODIES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/ai-chat/tool-artifact/grant", exchange -> {
            GRANT_CALLS.add(exchange.getRequestMethod() + " "
                    + URLDecoder.decode(exchange.getRequestURI().toString(), StandardCharsets.UTF_8));
            if (GRANT_REFUSED.get()) {
                writeJson(exchange, "{\"code\":\"40001\",\"msg\":\"存储通道暂不可用\",\"data\":null}");
                return;
            }
            String base = "http://localhost:" + server.getAddress().getPort();
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"objectKey\":\""
                    + STUB_KEY + "\",\"uploadUrl\":\"" + base + "/put-echo\"}}");
        });
        server.createContext("/put-echo", exchange -> {
            PUT_METHODS.add(exchange.getRequestMethod());
            PUT_CONTENT_TYPES.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            PUT_BODIES.add(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        GRANT_REFUSED.set(false);
        GRANT_CALLS.clear();
        PUT_METHODS.clear();
        PUT_CONTENT_TYPES.clear();
        PUT_BODIES.clear();
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Test
    void grantContract_getToCoreEndpointWithEncodedFileName() {
        new CoreGateway(baseUrl()).grantToolArtifactUpload("task42 摘要 (1).txt");
        assertEquals(1, GRANT_CALLS.size(), "one grant GET must happen");
        String call = GRANT_CALLS.get(0);
        assertTrue(call.startsWith("GET /internal/ai-chat/tool-artifact/grant?fileName="),
                "grant path pins the core internal contract, actual=" + call);
        assertTrue(call.contains("task42 摘要 (1).txt"),
                "file name must arrive verbatim after decoding, actual=" + call);
    }

    @Test
    void store_putsUtf8BytesAndReturnsObjectKeyAsAnchor() {
        ToolResultUploadGateway gateway = new ToolResultUploadGateway(
                new CoreGateway(baseUrl()), RestClient.builder());
        String anchor = gateway.store("工具全文-测试", 42L, 7L);
        assertEquals(STUB_KEY, anchor, "anchor is the canonical objectKey");
        assertEquals(1, GRANT_CALLS.size());
        String fileName = GRANT_CALLS.get(0)
                .substring(GRANT_CALLS.get(0).indexOf("fileName=") + "fileName=".length());
        assertTrue(fileName.startsWith("task42-") && fileName.endsWith(".txt"),
                "task-scope file name embeds the task anchor for ownership checks, actual="
                        + fileName);
        assertEquals(List.of("PUT"), PUT_METHODS, "full text must go up as one raw PUT");
        assertEquals(1, PUT_BODIES.size());
        assertArrayEquals("工具全文-测试".getBytes(StandardCharsets.UTF_8), PUT_BODIES.get(0),
                "payload bytes verbatim, no secondary encoding");
        assertTrue(PUT_CONTENT_TYPES.get(0) != null && PUT_CONTENT_TYPES.get(0).startsWith("text/plain"),
                "text payload declared as text/plain, actual=" + PUT_CONTENT_TYPES.get(0));
    }

    @Test
    void store_dialogContextUsesDialogFileNameWithoutTaskSegment() {
        ToolResultUploadGateway gateway = new ToolResultUploadGateway(
                new CoreGateway(baseUrl()), RestClient.builder());
        gateway.store("对话级工具全文", null, null);
        String fileName = GRANT_CALLS.get(0)
                .substring(GRANT_CALLS.get(0).indexOf("fileName=") + "fileName=".length());
        assertTrue(fileName.startsWith("dialog-") && fileName.endsWith(".txt"),
                "absent task scope degrades to the dialog prefix, actual=" + fileName);
    }

    @Test
    void refusedGrantPropagatesInsteadOfPretendingStored() {
        GRANT_REFUSED.set(true);
        ToolResultUploadGateway gateway = new ToolResultUploadGateway(
                new CoreGateway(baseUrl()), RestClient.builder());
        assertThrows(Exception.class, () -> gateway.store("任意全文", 42L, 7L),
                "a refusal must reach the folder's single fail-soft point, never a fake anchor");
        assertTrue(PUT_BODIES.isEmpty(), "no upload may happen after a refused grant");
    }
}
