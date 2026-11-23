package com.sixbean.testpike.aiservice.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.tool.domain.component.LlmFunctionCalling;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Single-source local core-response stub for ai-service tests (R40: the socket
 * start/stop pair and the raw JSON response write-up were copied across four
 * sibling test classes, so one transport-shape change had to be written four
 * times). The stub is an environment-level stand-in for the core service —
 * a real local HTTP socket on a free loopback port, not a business mock.
 */
public final class CoreStubHttp {

    private CoreStubHttp() {
    }

    /** Binds a stub server to a free loopback port and serves every path through the handler. */
    public static HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    /** Base URL a client under test should point at for this stub server. */
    public static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Stops the stub server immediately (no drain wait). */
    public static void shutdown(HttpServer server) {
        server.stop(0);
    }

    /**
     * Assembly caller wired to this stub server: the production FC assembly
     * facet reading the tool catalog over the local core-response door.
     */
    public static LlmFunctionCalling assemblyCaller(HttpServer server) {
        return new LlmFunctionCalling(new CoreGateway(baseUrl(server)), new ObjectMapper());
    }

    /** Writes a raw JSON body back with an application/json header and HTTP 200. */
    public static void respondRawJson(HttpExchange exchange, String body) throws IOException {
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }
}
