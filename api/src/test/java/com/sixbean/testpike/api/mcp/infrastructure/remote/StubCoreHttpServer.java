package com.sixbean.testpike.api.mcp.infrastructure.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;


public final class StubCoreHttpServer implements AutoCloseable {

    
    public record RecordedCall(String method, String path, String query, String body,
                               List<String> operatorHeaders, List<String> contentType) {

        String lastOperatorHeader() {
            return operatorHeaders.isEmpty() ? null : operatorHeaders.get(operatorHeaders.size() - 1);
        }
    }

    private final HttpServer server;
    private final List<RecordedCall> calls = new ArrayList<>();
    private final Deque<String> queuedResponses = new ArrayDeque<>();
    private String defaultResponse = ok("{}");

    private StubCoreHttpServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    public static StubCoreHttpServer start() throws IOException {
        return new StubCoreHttpServer();
    }

    
    public static String ok(String dataJson) {
        return "{\"code\":\"0\",\"msg\":null,\"data\":" + dataJson + "}";
    }

    
    public static String fail(String code, String msg) {
        return "{\"code\":\"" + code + "\",\"msg\":\"" + msg + "\",\"data\":null}";
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    
    public void enqueue(String responseJson) {
        queuedResponses.addLast(responseJson);
    }

    public void setDefaultResponse(String responseJson) {
        this.defaultResponse = responseJson;
    }

    public List<RecordedCall> calls() {
        return calls;
    }

    public RecordedCall call(int index) {
        return calls.get(index);
    }

    public int callCount() {
        return calls.size();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String query = exchange.getRequestURI().getRawQuery();
        List<String> operator = exchange.getRequestHeaders()
                .getOrDefault("X-Operator-Id", List.of());
        List<String> contentType = exchange.getRequestHeaders()
                .getOrDefault("Content-type", List.of());
        calls.add(new RecordedCall(exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(), query, body, operator, contentType));
        String response = queuedResponses.isEmpty() ? defaultResponse : queuedResponses.pollFirst();
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
