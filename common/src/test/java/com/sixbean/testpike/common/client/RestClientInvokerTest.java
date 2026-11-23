package com.sixbean.testpike.common.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.ResourceAccessException;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RestClientInvokerTest {

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"code\":\"0\",\"msg\":null,\"data\":\"ok\",\"success\":true}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    
    @Test
    void customReadTimeoutKillsSlowCall() {
        var rest = RestClientInvoker.defaultRestClient("http://127.0.0.1:" + port, Duration.ofSeconds(1));
        assertThrows(ResourceAccessException.class, () -> RestClientInvoker.call(rest,
                org.springframework.http.HttpMethod.GET, "/slow", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<String>>() {
                }, null));
    }

    
    @Test
    void customReadTimeoutAllowsWithinBudget() {
        var rest = RestClientInvoker.defaultRestClient("http://127.0.0.1:" + port, Duration.ofSeconds(10));
        String data = RestClientInvoker.call(rest,
                org.springframework.http.HttpMethod.GET, "/slow", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<String>>() {
                }, null);
        assertEquals("ok", data);
    }

    
    @Test
    void defaultFactoryStillWorks() {
        var rest = RestClientInvoker.defaultRestClient("http://127.0.0.1:" + port);
        String data = RestClientInvoker.call(rest,
                org.springframework.http.HttpMethod.GET, "/slow", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<String>>() {
                }, null);
        assertTrue("ok".equals(data));
    }
}
