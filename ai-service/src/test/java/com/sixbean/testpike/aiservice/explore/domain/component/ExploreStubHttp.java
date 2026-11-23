package com.sixbean.testpike.aiservice.explore.domain.component;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared response writer for the JDK HttpServer stubs used by the
 * explore-kernel test classes. Extracted from two identical copies
 * (single-source rule: the kernel regressions and the risk-ledger tests
 * now answer stub doors through this one writer).
 */
final class ExploreStubHttp {

    private ExploreStubHttp() {
    }

    /** Sends a 200 JSON body on the exchange. */
    static void writeJson(HttpExchange exchange, String json) throws IOException {
        writeRaw(exchange, json);
    }

    /** Sends a 200 body with a JSON content type (payload is not validated). */
    static void writeRaw(HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
