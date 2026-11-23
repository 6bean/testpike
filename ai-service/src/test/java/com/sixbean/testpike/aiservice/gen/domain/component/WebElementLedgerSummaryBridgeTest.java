package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.support.CoreStubHttp;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger prompt-summary bridge contracts (WE batch S5, piece B):
 * gate off = ZERO outbound calls; absent row = factory ON; rows render under
 * the max-rows/max-chars budgets with the title anchor; a failing core door
 * degrades to the empty segment (fail-soft, never blocks generation).
 * <p>Transport = real loopback HTTP via {@link CoreStubHttp} (environment
 * stand-in for core, not a business mock).
 */
class WebElementLedgerSummaryBridgeTest {

    private HttpServer server;
    private final List<String> seenPaths = new CopyOnWriteArrayList<>();
    private final AtomicInteger summaryCalls = new AtomicInteger();
    /** Stub payload knobs; set per test before the call under test. */
    private volatile String configEnabled = null;
    private volatile String configMaxRows = null;
    private volatile String configMaxChars = null;
    private volatile String summaryJson = "[]";
    private volatile boolean summaryFails = false;

    @BeforeEach
    void startStub() throws IOException {
        server = CoreStubHttp.startServer(exchange -> {
            String path = URLDecoder.decode(exchange.getRequestURI().getPath(),
                    StandardCharsets.UTF_8);
            seenPaths.add(path);
            if (path.startsWith("/internal/web-elements/prompt-summary")) {
                summaryCalls.incrementAndGet();
                if (summaryFails) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
                CoreStubHttp.respondRawJson(exchange,
                        "{\"code\":\"0\",\"data\":" + summaryJson + "}");
                return;
            }
            if (path.startsWith("/internal/configs/value")) {
                String query = exchange.getRequestURI().getQuery();
                String key = query == null ? "" : URLDecoder.decode(query,
                        StandardCharsets.UTF_8);
                String value = switch (nullToEmpty(extractKey(key))) {
                    case WebElementLedgerSummaryBridge.KEY_ENABLED -> configEnabled;
                    case WebElementLedgerSummaryBridge.KEY_MAX_ROWS -> configMaxRows;
                    case WebElementLedgerSummaryBridge.KEY_MAX_CHARS -> configMaxChars;
                    default -> null;
                };
                CoreStubHttp.respondRawJson(exchange, "{\"code\":\"0\",\"data\":"
                        + (value == null ? "null" : "\"" + value + "\"") + "}");
                return;
            }
            CoreStubHttp.respondRawJson(exchange, "{\"code\":\"0\",\"data\":null}");
        });
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            CoreStubHttp.shutdown(server);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String extractKey(String query) {
        for (String part : query.split("&")) {
            if (part.startsWith("key=")) {
                int amp = part.indexOf('&', 4);
                return amp < 0 ? part.substring(4) : part.substring(4, amp);
            }
        }
        return "";
    }

    private WebElementLedgerSummaryBridge bridge() {
        return new WebElementLedgerSummaryBridge(
                new CoreGateway(CoreStubHttp.baseUrl(server)), new ObjectMapper());
    }

    private static String rowsJson(String... namesAndCsses) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < namesAndCsses.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(namesAndCsses[i])
                    .append("\",\"pageKey\":\"https://shop.example.com/p\"")
                    .append(",\"anchors\":[{\"type\":\"css\",\"value\":\"")
                    .append(namesAndCsses[i + 1]).append("\"}]}");
        }
        return sb.append(']').toString();
    }

    @Test
    void gateOffMeansZeroOutboundCallsAndEmptySegment() {
        configEnabled = "0";
        String segment = bridge().summarySegment(1L, 2L);
        assertEquals("", segment, "explicit 0 restores the legacy prompt shape");
        assertEquals(0, summaryCalls.get(), "gate off must never burn the summary door");
    }

    @Test
    void absentRowIsFactoryOnAndSegmentCarriesAnchorAndPriorityLine() {
        summaryJson = rowsJson("登录按钮", "#login-btn");
        String segment = bridge().summarySegment(1L, 2L);
        assertTrue(segment.contains(WebElementLedgerSummaryBridge.SEGMENT_ANCHOR),
                "title anchor is the assertion surface");
        assertTrue(segment.contains("登录按钮") && segment.contains("css=#login-btn"));
        assertTrue(segment.contains("{{ELEM:"),
                "the reference-first instruction names the shared reference form");
        assertTrue(seenPaths.stream().anyMatch(p -> p.startsWith(
                "/internal/web-elements/prompt-summary")), "factory ON fetches the door");
    }

    @Test
    void maxRowsDropsRowsAndMaxCharsTruncatesWholeRowsOnly() {
        summaryJson = rowsJson("甲", "#a", "乙", "#b", "丙", "#c");
        configMaxRows = "2";
        String segment = bridge().summarySegment(1L, 2L);
        assertTrue(segment.contains("甲") && segment.contains("乙"), "first two rows land");
        assertFalse(segment.contains("丙"), "rows beyond max-rows are dropped");

        configMaxRows = null;
        configMaxChars = "200";
        summaryJson = rowsJson("甲", "#a", "乙", "#b", "丙", "#c", "丁", "#d", "戊", "#e");
        String capped = bridge().summarySegment(1L, 2L);
        assertTrue(capped.contains("甲"), "at least one row fits the character budget");
        assertFalse(capped.contains("戊"), "over-budget rows are dropped whole, never halved");
        assertTrue(capped.contains("截断"), "truncation stays honest, never silent");
    }

    @Test
    void coreDoorFailureDegradesToEmptySegmentNeverBlocksGeneration() {
        summaryFails = true;
        String segment = bridge().summarySegment(1L, 2L);
        assertEquals("", segment, "fail-soft: a broken summary door keeps the legacy bytes");
    }

    @Test
    void emptyLedgerAnswersEmptySegment() {
        summaryJson = "[]";
        assertEquals("", bridge().summarySegment(1L, 2L),
                "no confirmed elements = no injected noise");
    }
}
