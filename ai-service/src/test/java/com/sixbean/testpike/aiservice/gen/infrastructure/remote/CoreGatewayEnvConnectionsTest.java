package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

import com.sixbean.testpike.aiservice.support.CoreStubHttp;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire contract of the read-only enabled-connection name door (
 * mechanical gap fill): the gateway mirrors ONLY name/envId/status from the
 * core {@code GET /internal/projects/{id}/env-connections} rows (credential
 * values never reach ai-service), resolves the draft's env NAME to its id
 * through the existing envs door, keeps status=1 rows and matches
 * case-insensitively. A core-side refusal surfaces as ServiceException so the
 * caller can keep its original verdict instead of reading a door outage as
 * "the project has no database" (P18 three-state posture).
 */
class CoreGatewayEnvConnectionsTest {

    private static HttpServer server;
    private static String lastPath;

    @BeforeAll
    static void startStub() throws IOException {
        server = CoreStubHttp.startServer(CoreGatewayEnvConnectionsTest::handle);
    }

    @AfterAll
    static void stopStub() {
        CoreStubHttp.shutdown(server);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastPath = exchange.getRequestURI().toString();
        String body = switch (lastPath) {
            case "/internal/projects/7/env-connections" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":["
                            + "{\"id\":1,\"projectId\":7,\"envId\":5,\"name\":\"sit_db\","
                            + "\"dialect\":\"mysql\",\"jdbcUrl\":\"jdbc:h2:x\",\"username\":\"u\","
                            + "\"password\":\"******\",\"readonly\":true,\"maxPool\":2,"
                            + "\"status\":1},"
                            + "{\"id\":2,\"projectId\":7,\"envId\":5,\"name\":\"sit_rw\","
                            + "\"status\":0},"
                            + "{\"id\":3,\"projectId\":7,\"envId\":9,\"name\":\"uat_db\","
                            + "\"status\":1}]}";
            case "/internal/projects/7/envs" ->
                    "{\"code\":\"0\",\"msg\":\"ok\",\"data\":["
                            + "{\"id\":5,\"projectId\":7,\"name\":\"SIT\",\"envType\":2,"
                            + "\"baseUrl\":\"https://sit.test\"},"
                            + "{\"id\":9,\"projectId\":7,\"name\":\"UAT\",\"envType\":3,"
                            + "\"baseUrl\":\"https://uat.test\"}]}";
            case "/internal/projects/8/env-connections" ->
                    "{\"code\":\"40301\",\"msg\":\"项目不存在或无权访问\",\"data\":null}";
            default -> "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}";
        };
        CoreStubHttp.respondRawJson(exchange, body);
    }

    private static CoreGateway gateway() {
        return new CoreGateway(CoreStubHttp.baseUrl(server));
    }

    @Test
    void enabledNames_filterByEnvName_andKeepStatusOneOnly() {
        assertEquals(java.util.List.of("sit_db"),
                gateway().listEnabledConnectionNames(7L, "sit"));
    }

    @Test
    void unknownEnvName_honestEmpty_neverCrossEnv() {
        assertEquals(java.util.List.of(), gateway().listEnabledConnectionNames(7L, "PROD"));
    }

    @Test
    void blankEnvName_projectWideEnabledNames() {
        assertEquals(java.util.List.of("sit_db", "uat_db"),
                gateway().listEnabledConnectionNames(7L, ""));
    }

    @Test
    void coreRefusal_surfacesAsServiceException_notFoldedIntoEmpty() {
        assertThrows(ServiceException.class,
                () -> gateway().listEnabledConnectionNames(8L, "sit"));
    }
}
