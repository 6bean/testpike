package com.sixbean.testpike.admin.ai.infrastructure.remote.client;

import com.sun.net.httpserver.HttpServer;
import com.sixbean.testpike.admin.ai.api.dto.ExtractionEventView;
import com.sixbean.testpike.admin.ai.api.dto.ExtractionPreviewView;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-request-thread reads of the document door : the
 * live-tail pump runs on a virtual thread where the login ThreadLocal is
 * empty, so the two read-only observation calls must speak anonymously to
 * core — the project-ownership gate already sits on the admin controller and
 * core must not see a forged operator for them. Write calls keep forwarding
 * the signed-in operator unchanged.
 * <p>Infrastructure isolation at L2 with the JDK built-in HTTP server
 * (real socket, no mocking library): the stub records the operator header
 * it receives per path and answers the wire contract of core's internal
 * document door.
 */
class RestCoreDocumentClientOffThreadTest {

    private HttpServer server;
    /** Every path the stub served (call actually reached the wire). */
    private final java.util.Set<String> servedPaths = ConcurrentHashMap.newKeySet();
    /** Operator header seen per path — recorded only when present. */
    private final Map<String, String> receivedOperator = new ConcurrentHashMap<>();
    private RestCoreDocumentClient client;

    @BeforeEach
    void startStubCore() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            servedPaths.add(path);
            String operator = exchange.getRequestHeaders().getFirst(RestClientInvoker.OPERATOR_HEADER);
            if (operator != null) {
                receivedOperator.put(path, operator);
            }
            byte[] body = payloadFor(path).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = new RestCoreDocumentClient("http://127.0.0.1:" + server.getAddress().getPort(), 360);
        UserContext.clear();
    }

    @AfterEach
    void stopStubCore() {
        server.stop(0);
        UserContext.clear();
    }

    /** Wire payloads mirror core's Response envelope (code "0" = success). */
    private static String payloadFor(String path) {
        if (path.endsWith("/extraction-events")) {
            return "{\"code\":\"0\",\"data\":[{\"cursor\":1,\"level\":\"round\","
                    + "\"summary\":\"s\",\"detail\":\"d\",\"landedAt\":\"t\"}]}";
        }
        if (path.endsWith("/extraction")) {
            return "{\"code\":\"0\",\"data\":{\"documentId\":7,\"status\":4}}";
        }
        return "{\"code\":\"0\",\"data\":null}";
    }

    @Test
    void extractionEventsReadsWithoutLoginContext() {
        List<ExtractionEventView> rows = client.extractionEvents(7L, 0L, 50);

        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).cursor());
        String path = "/internal/documents/7/extraction-events";
        assertTrue(servedPaths.contains(path), "stub never saw the read call");
        assertNull(receivedOperator.get(path),
                "anonymity contract broken: read probe must not forward X-Operator-Id");
    }

    @Test
    void statusProbeReadsWithoutLoginContext() {
        ExtractionPreviewView view = client.extraction(7L, 1, 1);

        assertEquals(7L, view.documentId());
        assertEquals(4, view.status());
        assertTrue(servedPaths.contains("/internal/documents/7/extraction"),
                "stub never saw the status probe");
        assertNull(receivedOperator.get("/internal/documents/7/extraction"),
                "anonymity contract broken: status probe must not forward X-Operator-Id");
    }

    @Test
    void writeCallStillForwardsSignedInOperator() {
        UserContext.set(new CurrentUser(42L, "tester", "Tester", "QA", List.of("admin"), 0L, null));

        client.cancelExtraction(9L);

        assertEquals("42", receivedOperator.get("/internal/documents/9/extraction-cancel"),
                "write side must keep forwarding the signed-in operator verbatim");
    }

    @Test
    void writeCallStillRejectsWithoutLoginContext() {
        assertThrows(ServiceException.class, () -> client.cancelExtraction(9L),
                "write side keeps the 401 guard when no login context exists");
    }
}
