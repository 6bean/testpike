package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

import com.sixbean.testpike.common.exception.ServiceException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CoreGatewayFcAssemblyTest {

    private static HttpServer server;
    private static String lastPath;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", CoreGatewayFcAssemblyTest::handle);
        server.start();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastPath = exchange.getRequestURI().toString();
        String body = """
                {"code":"0","msg":"ok","data":[
                  {"toolId":11,"name":"kb_search","type":2,"execKind":1,
                   "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":30000,"write":false},
                  {"toolId":12,"name":"write_back","type":2,"execKind":3,
                   "inputSchemaJson":"{\\"x-write\\":true}","timeoutMs":5000,"write":true}]}
                """;
        byte[] resp = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    private static CoreGateway gateway() {
        return new CoreGateway("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void FC装配读取_路径与七字段镜像解析() {
        List<ToolFcAssemblyRemoteItem> items = gateway().getProjectFcAssembly(7L);
        assertEquals("/internal/tools/project/7/assembly", lastPath);
        assertEquals(2, items.size());
        ToolFcAssemblyRemoteItem first = items.get(0);
        assertEquals(11L, first.toolId());
        assertEquals("kb_search", first.name());
        assertEquals(2, first.type());
        assertEquals(1, first.execKind());
        assertEquals("{\"type\":\"object\"}", first.inputSchemaJson());
        assertEquals(30000, first.timeoutMs());
        assertEquals(false, first.write());
        assertEquals(3, items.get(1).execKind());
        assertEquals(true, items.get(1).write());
    }

    @Test
    void FC装配业务错误_上抛由调用方fail_soft() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] resp = "{\"code\":\"40401\",\"msg\":\"项目不存在\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            CoreGateway gateway = gateway();
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> gateway.getProjectFcAssembly(999L));
            assertTrue(ex.getMessage().contains("项目不存在"), "人话错误透传: " + ex.getMessage());
        } finally {
            server.stop(0);
            startStub();
        }
    }
}
