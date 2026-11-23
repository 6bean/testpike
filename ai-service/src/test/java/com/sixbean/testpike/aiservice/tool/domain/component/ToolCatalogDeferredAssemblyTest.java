package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-5 deferred tool-catalog assembly : above the configured
 * executable-tool count the FC request keeps only the core set resident and
 * the rest ride a compact system-prompt catalog behind the built-in
 * {@code search_tools} meta-tool; below it the legacy shape is byte-identical
 * (all tools resident, no meta def, no catalog note). The routing index must
 * still cover EVERY assembled candidate — deferral shrinks the prompt, never
 * the authorization surface (five-way gate intersection untouched).
 * <p>Assembly/config source = real HTTP chain against the local core response
 * stub (environment-level stand-in, not a business mock — LlmFunctionCallingTest
 * posture). Stub buckets: /9 = 25 executables (over default threshold 20),
 * /1 = 3 executables (under), /2 = empty (empty-plan gate).
 */
class ToolCatalogDeferredAssemblyTest {

    private static HttpServer server;
    private static LlmFunctionCalling calling;
    private static FcToolCatalog catalog;
    /** Mutable so single tests can flip the threshold/core-set answers. */
    private static volatile String thresholdAnswer = "20";
    private static volatile String coreSetAnswer = "";

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ToolCatalogDeferredAssemblyTest::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        CoreGateway gateway = new CoreGateway(base);
        ObjectMapper mapper = new ObjectMapper();
        catalog = new FcToolCatalog(gateway, mapper);
        calling = new LlmFunctionCalling(gateway, mapper, catalog);
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static String executableTools(int count) {
        return IntStream.range(0, count).mapToObj(i -> String.format(
                "{\"toolId\":%d,\"name\":\"tool_%02d\",\"type\":2,\"execKind\":1,"
                        + "\"inputSchemaJson\":\"{\\\"type\\\":\\\"object\\\","
                        + "\\\"description\\\":\\\"desc_%02d\\\"}\",\"timeoutMs\":5000,"
                        + "\"write\":false}", 100 + i, i, i))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body;
        if (path.equals("/internal/tools/project/9/assembly")) {
            body = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":" + executableTools(25) + "}";
        } else if (path.equals("/internal/tools/project/1/assembly")) {
            body = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":" + executableTools(3) + "}";
        } else if (path.equals("/internal/tools/project/2/assembly")) {
            body = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":[]}";
        } else if (path.equals("/internal/configs/value")) {
            String q = URLDecoder.decode(exchange.getRequestURI().getQuery(), StandardCharsets.UTF_8);
            String value = q.contains(FcToolCatalog.KEY_CORE_TOOLS) ? coreSetAnswer
                    : q.contains(FcToolCatalog.KEY_EXPAND_MAX) ? "8" : thresholdAnswer;
            body = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":\"" + value + "\"}";
        } else {
            body = "{\"code\":\"0\",\"msg\":\"ok\",\"data\":null}";
        }
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    /** Below threshold: legacy shape, every tool resident, no meta def, no note. */
    @Test
    void underThresholdKeepsLegacyResidentShape() {
        FcPlan plan = calling.assemblePlan(1, null);
        assertEquals(3, plan.tools().size());
        assertTrue(plan.deferred().isEmpty());
        assertFalse(plan.tools().stream().anyMatch(d -> FcToolCatalog.isSearchCall(d.function().name())));
        ChatCompletionRequest req = new ChatCompletionRequest("m",
                java.util.List.of(new ChatMessage("system", "S")), null, null, null, null);
        ChatCompletionRequest decorated = calling.decorate(req, plan);
        String system = decorated.messages().get(0).content();
        assertFalse(system.contains("search_tools"));
    }

    /** Over threshold: core set (empty here) + search_tools only; full index. */
    @Test
    void overThresholdDefersNonCoreTools() {
        FcPlan plan = calling.assemblePlan(9, null);
        assertEquals(1, plan.tools().size());
        assertTrue(FcToolCatalog.isSearchCall(plan.tools().get(0).function().name()));
        assertEquals(25, plan.deferred().size());
        assertEquals(25, plan.index().size());
        assertTrue(plan.index().containsKey("tool_24"));
    }

    /** Core-set names stay resident next to the meta def; rest deferred. */
    @Test
    void coreSetMembersStayResident() {
        String previous = coreSetAnswer;
        coreSetAnswer = "tool_00,tool_01";
        try {
            FcPlan plan = calling.assemblePlan(9, null);
            assertEquals(3, plan.tools().size());
            assertEquals(23, plan.deferred().size());
            assertTrue(plan.index().containsKey("tool_00"));
        } finally {
            coreSetAnswer = previous;
        }
    }

    /** Non-positive threshold = catalog explicitly disabled (legacy shape). */
    @Test
    void nonPositiveThresholdDisablesCatalog() {
        String previous = thresholdAnswer;
        thresholdAnswer = "0";
        try {
            FcPlan plan = calling.assemblePlan(9, null);
            assertEquals(25, plan.tools().size());
            assertTrue(plan.deferred().isEmpty());
        } finally {
            thresholdAnswer = previous;
        }
    }

    /** Empty-plan gate: byte-identical EMPTY even with a live catalog bean. */
    @Test
    void emptyProjectStillYieldsEmptyPlan() {
        assertSame(FcPlan.EMPTY, calling.assemblePlan(2, null));
    }

    /** Active catalog: compact note lines ride the system marker injection. */
    @Test
    void decorateInjectsCompactCatalogNote() {
        FcPlan plan = calling.assemblePlan(9, null);
        ChatCompletionRequest req = new ChatCompletionRequest("m",
                java.util.List.of(new ChatMessage("system", "S")), null, null, null, null);
        ChatCompletionRequest decorated = calling.decorate(req, plan);
        String system = decorated.messages().get(0).content();
        assertTrue(system.contains("tool_24"));
        assertTrue(system.contains("desc_24"));
        assertTrue(system.contains(FcToolCatalog.NAME));
    }

    /** Name query matches case-insensitively and caps expansion at expand-max. */
    @Test
    void searchMatchesAndCapsExpansion() {
        FcPlan plan = calling.assemblePlan(9, null);
        FcCatalogSearchOutcome res = catalog.execute("{\"query\":\"TOOL_1\"}", plan.deferred());
        assertTrue(res.success());
        assertEquals(8, res.expandedTools().size());
        assertTrue(res.content().contains("tool_10"));
        assertEquals(1, res.expandedTools().stream()
                .filter(d -> d.function().name().equals("tool_10")).count());
    }

    /** Zero-hit search = honest empty answer (the search ran, no expansion);
     *  blank query = readable refusal. Neither ever throws. */
    @Test
    void searchRefusesInvisiblyNoMatchFailSoft() {
        FcPlan plan = calling.assemblePlan(9, null);
        var miss = catalog.execute("{\"query\":\"zzz-none\"}", plan.deferred());
        assertTrue(miss.success(), "零命中=检索正常完成，不是故障");
        assertTrue(miss.expandedTools().isEmpty(), "零命中=零展开");
        assertTrue(miss.content().contains("apiAccess.catalog"), "空账必带下一步指路");
        assertFalse(catalog.execute("{}", plan.deferred()).success(), "空查询=非法调用，仍记拒");
    }
}
