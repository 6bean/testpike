package com.sixbean.testpike.aiservice.explore.infrastructure.remote;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ExploreEvidenceGatewayTest {

    private static HttpServer server;
    private static final List<String> GRANT_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> PUT_METHODS = new CopyOnWriteArrayList<>();
    private static final List<String> PUT_PATHS = new CopyOnWriteArrayList<>();
    private static final List<String> PUT_CONTENT_TYPES = new CopyOnWriteArrayList<>();
    private static final List<byte[]> PUT_BODIES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/explore/sessions/", exchange -> {
            GRANT_CALLS.add(exchange.getRequestMethod() + " "
                    + URLDecoder.decode(exchange.getRequestURI().toString(), StandardCharsets.UTF_8));
            String base = "http://localhost:" + server.getAddress().getPort();
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"sessionId\":901,"
                    + "\"objectKey\":\"7/explore/202609/abcd1234-screenshot_shot.png\","
                    + "\"uploadUrl\":\"" + base + "/put-echo\"}}");
        });
        server.createContext("/put-echo", exchange -> {
            PUT_METHODS.add(exchange.getRequestMethod());
            PUT_PATHS.add(exchange.getRequestURI().getPath());
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

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        GRANT_CALLS.clear();
        PUT_METHODS.clear();
        PUT_PATHS.clear();
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
    void grant链路_GET路径与query参数契约_编码透传() {
        new CoreGateway(baseUrl()).grantExploreEvidence(901L, "screenshot", "首页 shot (1).png");
        assertEquals(1, GRANT_CALLS.size(), "必须发起一次 grant GET");
        String call = GRANT_CALLS.get(0);
        assertTrue(call.startsWith("GET /internal/explore/sessions/901/evidence-grant?"),
                "grant 路径钉死 core internal 契约，实际=" + call);
        assertTrue(call.contains("kind=screenshot"), "kind 参数线名，实际=" + call);
        assertTrue(call.contains("fileName="), "fileName 参数线名，实际=" + call);
        assertTrue(call.contains("首页 shot (1).png"), "文件名须原值到达，实际=" + call);
    }

    @Test
    void upload链路_PUT裸字节到预签名URL_返回objectKey() {
        ExploreEvidenceGateway gateway = new ExploreEvidenceGateway(
                new CoreGateway(baseUrl()), RestClient.builder());
        byte[] payload = "png-bytes-éè".getBytes(StandardCharsets.UTF_8);
        String objectKey = gateway.uploadEvidence(901L, "screenshot", "shot.png",
                payload, "image/png");
        assertEquals("7/explore/202609/abcd1234-screenshot_shot.png", objectKey,
                "须回 grant 的 objectKey（步进回写落库引用）");
        assertEquals(List.of("PUT"), PUT_METHODS, "留证必须 PUT 裸字节至 uploadUrl");
        assertEquals(List.of("/put-echo"), PUT_PATHS);
        assertEquals(List.of("image/png"), PUT_CONTENT_TYPES, "contentType 须透传");
        assertEquals(1, PUT_BODIES.size());
        assertArrayEquals(payload, PUT_BODIES.get(0), "载荷字节原样透传（禁二次编码）");
    }
}
