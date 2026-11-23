package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Local OpenAI-compatible stub that answers 200 and keeps every request body it
 * receives, newest first. L2 infrastructure only — the production HTTP client and
 * serialization run untouched, so wire-level assertions on the request JSON are
 * genuine evidence (no provider-level mocking).
 */
class LlmCapturingStubServer implements AutoCloseable {

    private final HttpServer server;
    private volatile String lastRequestBody = null;

    LlmCapturingStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    /** Base url in OpenAI-compatible form ({@code http://127.0.0.1:port/v1}). */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Raw body of the most recent request the stub served. */
    String lastRequestBody() {
        return lastRequestBody;
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastRequestBody = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
        LlmTestFixtures.respondJson(exchange, "ok");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
