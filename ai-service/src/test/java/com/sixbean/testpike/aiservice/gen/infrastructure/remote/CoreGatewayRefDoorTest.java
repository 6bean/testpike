package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

import com.sixbean.testpike.aiservice.support.CoreStubHttp;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire contract of the two internal detail doors the dialog @-mention chips
 * ride : {@code GET /internal/batches/{id}} and
 * {@code GET /internal/defects/{id}}. Posture identical to
 * CoreGatewayFrameLedgerTest — a local core-response stub stands in for the
 * core service (environment-level, not a business mock); assertions pin the
 * path shape the core controllers expect plus the business-code refusal
 * surfacing as ServiceException for the resolver's named-refusal line.
 */
class CoreGatewayRefDoorTest {

    private static HttpServer server;
    private static String lastPath;
    private static String lastMethod;

    @BeforeAll
    static void startStub() throws IOException {
        server = CoreStubHttp.startServer(CoreGatewayRefDoorTest::handle);
    }

    @AfterAll
    static void stopStub() {
        CoreStubHttp.shutdown(server);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastPath = exchange.getRequestURI().toString();
        lastMethod = exchange.getRequestMethod();
        String body = switch (lastPath) {
            case "/internal/batches/5" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"id\":5,\"projectId\":3,"
                            + "\"batchNo\":\"B-20261027-01\",\"name\":\"夜间回归批\"}}";
            case "/internal/defects/8" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"id\":8,\"caseId\":11,"
                            + "\"defectKey\":\"PIKE-8\",\"defectStatus\":\"open\"}}";
            case "/internal/batches/999" ->
                    "{\"code\":\"40401\",\"msg\":\"批次不存在\",\"data\":null}";
            default -> "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}";
        };
        CoreStubHttp.respondRawJson(exchange, body);
    }

    private static CoreGateway gateway() {
        return new CoreGateway(CoreStubHttp.baseUrl(server));
    }

    @Test
    void getBatch_readsDetailOverGetAndUnwrapsTheMirror() {
        Map<String, Object> row = gateway().getBatch(5L);
        assertEquals("GET", lastMethod);
        assertEquals("/internal/batches/5", lastPath);
        assertEquals("夜间回归批", row.get("name"));
        assertEquals(3, ((Number) row.get("projectId")).longValue());
    }

    @Test
    void getDefect_readsLinkRowWithOwningCaseId() {
        Map<String, Object> row = gateway().getDefect(8L);
        assertEquals("GET", lastMethod);
        assertEquals("/internal/defects/8", lastPath);
        assertEquals(11L, ((Number) row.get("caseId")).longValue());
        assertEquals("PIKE-8", row.get("defectKey"));
    }

    @Test
    void missingBatchSurfacesAsServiceExceptionNamingTheReason() {
        // The resolver turns this into a named refusal — the door must not
        // swallow the business error code into a null map.
        ServiceException refused = assertThrows(ServiceException.class,
                () -> gateway().getBatch(999L));
        assertTrue(refused.getMessage().contains("批次不存在"), refused.getMessage());
    }
}
