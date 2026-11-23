package com.sixbean.testpike.aiservice.mock.infrastructure.remote;

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

/**
 * L2 client-contract tests for the draft-bundle upload gateway (
 * ; ExploreEvidenceGatewayTest precedent — a JDK HttpServer stub pins
 * the wire against the real core controller: ① GET path + encoded fileName
 * query of the grant door ② raw-bytes PUT to the presigned url with JSON
 * content type ③ the object key of the grant comes back for the preview
 * write-back. The real presigned chain belongs to L3/L4; this seam must never
 * pull a s3 dependency (layering red line).
 */
class MockDraftArtifactGatewayTest {

    private static HttpServer server;
    private static final List<String> GRANT_CALLS = new CopyOnWriteArrayList<>();
    private static final List<String> PUT_METHODS = new CopyOnWriteArrayList<>();
    private static final List<String> PUT_CONTENT_TYPES = new CopyOnWriteArrayList<>();
    private static final List<byte[]> PUT_BODIES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/mock/jobs/", exchange -> {
            GRANT_CALLS.add(exchange.getRequestMethod() + " "
                    + URLDecoder.decode(exchange.getRequestURI().toString(), StandardCharsets.UTF_8));
            String base = "http://localhost:" + server.getAddress().getPort();
            writeJson(exchange, "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"jobId\":77,"
                    + "\"objectKey\":\"3/mock-draft/202610/abcd1234-draft.json\","
                    + "\"uploadUrl\":\"" + base + "/put-echo\"}}");
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

    @org.junit.jupiter.api.BeforeEach
    void reset() {
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
    void grantDoor_getPathAndFileNameQueryReachCoreVerbatim() {
        new CoreGateway(baseUrl()).grantMockDraftArtifact(77L, "draft 下单 (1).json");
        assertEquals(1, GRANT_CALLS.size(), "the grant door fires exactly once");
        String call = GRANT_CALLS.get(0);
        assertTrue(call.startsWith("GET /internal/mock/jobs/77/artifact-grant?"),
                "grant path pins the core internal contract, got: " + call);
        assertTrue(call.contains("fileName=draft 下单 (1).json"),
                "fileName arrives url-decoded verbatim (spaces/brackets/CJK survive), got: " + call);
    }

    @Test
    void upload_putsRawBundleBytesAndReturnsObjectKey() {
        MockDraftArtifactGateway gateway = new MockDraftArtifactGateway(
                new CoreGateway(baseUrl()), RestClient.builder());
        byte[] bundle = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
        String key = gateway.uploadDraftBundle(77L, "draft.json", bundle);
        assertEquals("3/mock-draft/202610/abcd1234-draft.json", key,
                "the grant's object key is what lands in result_preview_ref");
        assertEquals(List.of("PUT"), PUT_METHODS, "bundle bytes travel via PUT to the presigned url");
        assertEquals(List.of("application/json"), PUT_CONTENT_TYPES);
        assertEquals(1, PUT_BODIES.size());
        assertArrayEquals(bundle, PUT_BODIES.get(0), "payload bytes pass through untouched");
    }
}
