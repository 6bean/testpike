package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-list scope of the two degradation-chain readers (generation vs embedding).
 *
 * <p>Why this exists: the chat chain and the embedding chain share one core
 * endpoint, {@code GET /internal/llm-configs/briefs}, whose contract is
 * "modelType absent = every enabled row, whatever its domain". The embedding
 * reader already asked for {@code modelType=2}; the chat reader asked for
 * nothing, so an embedding-only inventory handed the chat chain an embedding
 * model name. The provider then rejects it ("Unsupported model ... for OpenAI
 * compatibility mode"), the gateway parks that deployment in cooldown, and the
 * legitimate embedding calls behind it fail with 429 "No deployments
 * available" — one wrongly-scoped read poisoning both chains at once.
 *
 * <p>L2 isolates only the infrastructure HTTP boundary (JDK stub that mirrors
 * the core filter); the gateway client itself is the real RestClient. Pins: the
 * chat reader requests {@code modelType=1} explicitly and receives only the
 * generation row, the embedding reader keeps {@code modelType=2}.
 */
class CoreGatewayModelScopeTest {

    private static HttpServer server;
    private static final List<String> PATHS = new CopyOnWriteArrayList<>();

    private static final String GEN_ROW = """
            {"id":11,"name":"gen","provider":"openai","modelName":"qwen3.8-flash",
             "apiKey":"***","baseUrl":"http://localhost:4000/v1","source":"default",
             "promptLimitTokens":1048576,"tokenizerFamily":"auto","modelType":1,
             "embeddingDimension":null,"cacheHint":true,"structuredOutput":true}""";

    private static final String EMB_ROW = """
            {"id":12,"name":"emb","provider":"dashscope-compatible","modelName":"text-embedding-v4",
             "apiKey":"***","baseUrl":"http://localhost:4000/v1","source":"default",
             "promptLimitTokens":null,"tokenizerFamily":"auto","modelType":2,
             "embeddingDimension":1536,"cacheHint":true,"structuredOutput":null}""";

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/llm-configs/briefs", CoreGatewayModelScopeTest::answer);
        server.start();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Answers like core: modelType absent = every domain, 1/2 = that domain only. */
    private static void answer(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        PATHS.add(uri);
        String rows = uri.contains("modelType=1") ? GEN_ROW
                : uri.contains("modelType=2") ? EMB_ROW
                : GEN_ROW + "," + EMB_ROW;
        byte[] out = ("{\"code\":\"0\",\"msg\":null,\"success\":true,\"data\":[" + rows + "]}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private static CoreGateway gateway() {
        return new CoreGateway("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void chatChainReaderRequestsGenerationDomainOnly() {
        PATHS.clear();
        var rows = gateway().listEnabledLlmConfigs();
        assertEquals(1, PATHS.size(), "one briefs read per chain build");
        assertTrue(PATHS.get(0).contains("modelType=1"),
                "the chat degradation chain must never be handed embedding rows, got " + PATHS.get(0));
        assertEquals(1, rows.size(), "core filters by domain, so only the generation row comes back");
        assertEquals("qwen3.8-flash", rows.get(0).modelName());
    }

    @Test
    void embeddingChainReaderRequestsEmbeddingDomainOnly() {
        PATHS.clear();
        var rows = gateway().listEnabledEmbeddingConfigs();
        assertEquals(1, PATHS.size());
        assertTrue(PATHS.get(0).contains("modelType=2"),
                "embedding chain scope must stay explicit, got " + PATHS.get(0));
        assertEquals(1, rows.size());
        assertEquals("text-embedding-v4", rows.get(0).modelName());
    }
}
