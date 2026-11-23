package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screenshot byte fetcher contract : the
 * download leg runs against a REAL local HTTP server (JDK
 * com.sun.net.httpserver) — the fetcher is the one place this application
 * point touches the network, so its ladder is exercised without mocks.
 * <p>Assertions: ① 2xx body returns the exact bytes; ② non-2xx raises an
 * operator-readable reason naming the status (degrades the dialog leg, never
 * a half image); ③ an over-cap body is rejected with the cap named.
 */
class FailureScreenshotBytesFetcherTest {

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(png);
            }
        });
        server.createContext("/missing", exchange -> {
            byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/oversize", exchange -> {
            byte[] body = new byte[4096];
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void okResponseReturnsTheExactBytes() {
        byte[] fetched = new FailureScreenshotBytesFetcher()
                .fetch(baseUrl + "/ok", 8192);

        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4}, fetched);
    }

    @Test
    void nonSuccessStatusRaisesReasonNamingTheStatusCode() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new FailureScreenshotBytesFetcher().fetch(baseUrl + "/missing", 8192));

        assertTrue(failure.getMessage().contains("404"), failure.getMessage());
    }

    @Test
    void oversizeBodyIsRejectedWithTheCapNamed() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new FailureScreenshotBytesFetcher().fetch(baseUrl + "/oversize", 64));

        assertTrue(failure.getMessage().contains("64"), failure.getMessage());
    }
}
