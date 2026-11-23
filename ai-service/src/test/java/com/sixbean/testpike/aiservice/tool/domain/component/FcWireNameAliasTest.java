package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.support.CoreStubHttp;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level tool-name alias contract for the FC assembly facet.
 *
 * <p>Strict OpenAI-compatible providers reject {@code tools[].function.name}
 * that violates {@code ^[a-zA-Z0-9_-]{1,64}$} with an HTTP 400 that kills the
 * whole generation request. The catalog (tp_tool) is an outward MCP contract
 * and legitimately carries dotted and CJK names, so the assembly facet must
 * re-issue a provider-legal wire name for such entries while keeping the
 * original name routable on the response side (alias and original name both
 * resolve to the same assembly fact; legal names pass through unchanged).
 */
class FcWireNameAliasTest {

    /** Provider-side pattern observed in the LiteLLM/deepseek 400 verdict. */
    private static final Pattern PROVIDER_LEGAL = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static HttpServer server;
    private static LlmFunctionCalling calling;

    @BeforeAll
    static void startStub() throws IOException {
        server = CoreStubHttp.startServer(FcWireNameAliasTest::handle);
        calling = CoreStubHttp.assemblyCaller(server);
    }

    @AfterAll
    static void stopStub() {
        CoreStubHttp.shutdown(server);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String body = switch (exchange.getRequestURI().toString()) {
            // Mixed catalog: legal name, two dot-separated names, one CJK name.
            case "/internal/tools/project/7/assembly" -> """
                    {"code":"0","msg":"ok","data":[
                      {"toolId":70,"name":"kb_search","type":2,"execKind":1,
                       "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":false},
                      {"toolId":71,"name":"case.create","type":2,"execKind":1,
                       "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":false},
                      {"toolId":72,"name":"ocr.text_extract","type":2,"execKind":1,
                       "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":false},
                      {"toolId":73,"name":"造数名·批量","type":2,"execKind":1,
                       "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":false}]}
                    """;
            // Collision bucket: the alias of "a.b" would otherwise steal the
            // original name of the real tool "a_b".
            case "/internal/tools/project/8/assembly" -> """
                    {"code":"0","msg":"ok","data":[
                      {"toolId":80,"name":"a.b","type":2,"execKind":1,
                       "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":false},
                      {"toolId":81,"name":"a_b","type":2,"execKind":1,
                       "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":false}]}
                    """;
            // Two overlong names sharing a 70-character prefix: truncation must
            // not collapse them onto one wire name.
            case "/internal/tools/project/9/assembly" -> {
                String prefix = "x".repeat(70);
                yield """
                        {"code":"0","msg":"ok","data":[
                          {"toolId":90,"name":"%sAAA","type":2,"execKind":1,
                           "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":false},
                          {"toolId":91,"name":"%sBBB","type":2,"execKind":1,
                           "inputSchemaJson":"{\\"type\\":\\"object\\"}","timeoutMs":5000,"write":false}]}
                        """.formatted(prefix, prefix);
            }
            default -> "{\"code\":\"40401\",\"msg\":\"项目不存在\",\"data\":null}";
        };
        CoreStubHttp.respondRawJson(exchange, body);
    }

    private static FcPlan plan(long projectId) {
        return calling.assemblePlan(projectId, null);
    }

    private static List<String> wireNames(FcPlan plan) {
        return plan.tools().stream().map(ChatToolDef::function).map(f -> f.name()).toList();
    }

    /** Same toolId as the assembly row — identifies which fact a name resolves to. */
    private static Long routedToolId(FcPlan plan, String name) {
        var item = plan.index().get(name);
        return item == null ? null : item.toolId();
    }

    @Test
    void everyIssuedWireNameMatchesTheProviderCharsetPattern() {
        FcPlan mixed = plan(7);
        assertEquals(4, mixed.tools().size());
        for (String wire : wireNames(mixed)) {
            assertTrue(PROVIDER_LEGAL.matcher(wire).matches(),
                    "function.name must satisfy the provider pattern, got: " + wire);
        }
    }

    @Test
    void legalCatalogNamePassesThroughUnchanged() {
        FcPlan mixed = plan(7);
        assertTrue(wireNames(mixed).contains("kb_search"),
                "already-legal names must not be rewritten (no pointless churn)");
    }

    @Test
    void aliasAndOriginalNameResolveToTheSameAssemblyFact() {
        FcPlan mixed = plan(7);
        Long originalKey = routedToolId(mixed, "case.create");
        assertEquals(71L, originalKey, "the original catalog name stays routable");
        for (String wire : wireNames(mixed)) {
            assertNotNull(routedToolId(mixed, wire),
                    "every issued wire name must route back to an assembly fact: " + wire);
        }
        assertSame(mixed.index().get("case.create"), mixed.index().get("case_create"),
                "dotted name dispatches through its underscore alias to the same item");
    }

    @Test
    void aliasNeverStealsTheOriginalNameOfAnotherTool() {
        FcPlan clash = plan(8);
        List<String> wires = wireNames(clash);
        assertEquals(2, wires.size());
        assertTrue(wires.contains("a_b"), "the real tool keeps its own original wire name");
        String dottedAlias = wires.stream().filter(w -> !w.equals("a_b")).findFirst().orElseThrow();
        assertTrue(PROVIDER_LEGAL.matcher(dottedAlias).matches(), "bad alias: " + dottedAlias);
        assertEquals(81L, routedToolId(clash, "a_b"), "original name routes to the real row");
        assertEquals(80L, routedToolId(clash, "a.b"), "the dotted row stays routable by its own name");
        assertEquals(80L, routedToolId(clash, dottedAlias), "its alias routes to the dotted row");
    }

    @Test
    void overlongNamesTruncateDistinctlyWithinSixtyFourChars() {
        FcPlan longs = plan(9);
        List<String> wires = wireNames(longs);
        assertEquals(2, wires.size());
        for (String wire : wires) {
            assertTrue(wire.length() <= 64, "wire name too long: " + wire);
            assertTrue(PROVIDER_LEGAL.matcher(wire).matches(), "bad wire name: " + wire);
        }
        assertNotEquals(wires.get(0), wires.get(1), "prefix-sharing long names must not collapse");
    }

    @Test
    void hallucinatedToolNameStaysUnroutable() {
        assertNull(plan(7).index().get("no_such_tool"),
                "the widening of routable keys must never accept unknown names");
    }
}
