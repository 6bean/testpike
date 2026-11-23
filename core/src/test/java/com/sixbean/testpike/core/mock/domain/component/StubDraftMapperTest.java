package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.spec.domain.component.OpenApiSpecParser;
import com.sixbean.testpike.core.spec.domain.component.ParsedApiSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract-direct stub mapping : a parsed
 * tp_api_spec endpoint list turns into save-ready stub drafts with zero LLM
 * tokens — the same deterministic channel-A posture SpecDraftMapper set for
 * requests.
 * <p>Pinned behaviour:
 * (1) verbs the match kernel cannot serve (TRACE...) are dropped, everything
 * else carries endpointKey / title / method / path through to the draft;
 * (2) an OpenAPI template path with {var} captures becomes pathMatchType
 * template, a plain path stays exact — the stub then matches the same URL
 * shape the contract declares;
 * (3) provenance is stamped openapi + spec id anchor so the stale badge chain
 * /3) can retrace which contract a stub came from;
 * (4) the response document follows the builder contract (status/headers/
 * bodyMode/body): status = lowest declared 2xx (fallback 200), path/query
 * parameters echo through the template kernel ({@code mock.pathVar}/{@code
 * mock.query}), format-recognised fields synthesise Chinese-domain data via
 * the seeded {@code gen.*} whitelist, and a response without a declared
 * content block stays static-empty rather than inventing a body;
 * (5) determinism: the same spec maps to byte-identical drafts on every run
 * (seeds hash from name+location, never system randomness) — the quality and
 * cost floor of channel A;
 * (6) rule names clamp to the 120-column width instead of failing the save.
 */
class StubDraftMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OpenApiSpecParser parser = new OpenApiSpecParser();
    private final StubDraftMapper mapper = new StubDraftMapper();

    private static final String API_JSON = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Order API", "version": "1.0.0"},
              "paths": {
                "/orders/{id}": {
                  "get": {
                    "operationId": "getOrder",
                    "summary": "查询订单详情",
                    "parameters": [
                      {"name": "id", "in": "path", "required": true,
                       "schema": {"type": "integer"}},
                      {"name": "contact", "in": "query", "required": false,
                       "schema": {"type": "string", "format": "phone"}}
                    ],
                    "responses": {
                      "200": {"description": "ok",
                              "content": {"application/json":
                                  {"schema": {"type": "object"}}}},
                      "404": {"description": "missing"}
                    }
                  },
                  "trace": {
                    "responses": {"200": {"description": "ok"}}
                  }
                },
                "/notes": {
                  "post": {
                    "operationId": "createNote",
                    "responses": {"201": {"description": "created"}}
                  }
                }
              }
            }
            """;

    private List<MockStubDraft> drafts() {
        ParsedApiSpec parsed = parser.parse(
                API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        return mapper.fromParsedSpec(77L, parsed);
    }

    @Test
    void unservedVerbDroppedAndContractFieldsCarriedThrough() throws Exception {
        List<MockStubDraft> out = drafts();
        assertEquals(2, out.size(), "TRACE is not in the match kernel verb set");
        MockStubDraft order = out.stream()
                .filter(d -> d.endpointKey().equals("GET /orders/{id}"))
                .findFirst().orElseThrow();
        assertEquals("查询订单详情", order.name());
        assertEquals("GET", order.method());
        assertEquals("template", order.pathMatchType());
        assertEquals("/orders/{id}", order.pathPattern());
        assertEquals("[]", order.matchersJson());
        assertEquals(MockStubDraft.SOURCE_OPENAPI, order.source());
        assertEquals("spec:77", order.sourceRef());
    }

    @Test
    void responseDocumentEchoesParamsAndSynthesisesFormatData() throws Exception {
        MockStubDraft order = drafts().stream()
                .filter(d -> d.endpointKey().equals("GET /orders/{id}"))
                .findFirst().orElseThrow();
        JsonNode doc = MAPPER.readTree(order.responseJson());
        assertEquals(200, doc.path("status").asInt(), "lowest declared 2xx wins");
        assertEquals("template", doc.path("bodyMode").asText());
        String body = doc.path("body").asText();
        assertTrue(body.contains("{{mock.pathVar.id}}"),
                "path variable echoes through the template kernel: " + body);
        assertTrue(body.contains("${gen.phone(seed="),
                "phone-format field rides the seeded genkit whitelist: " + body);
        assertEquals("application/json",
                doc.path("headers").path("Content-Type").asText());
    }

    @Test
    void responseWithoutDeclaredContentStaysStaticEmpty() throws Exception {
        MockStubDraft note = drafts().stream()
                .filter(d -> d.endpointKey().equals("POST /notes"))
                .findFirst().orElseThrow();
        JsonNode doc = MAPPER.readTree(note.responseJson());
        // the parsed snapshot only carries codes that declare content, so a
        // content-less 201 is invisible to the mapper — 200 shell fallback
        assertEquals(200, doc.path("status").asInt());
        assertEquals("static", doc.path("bodyMode").asText());
        assertEquals("", doc.path("body").asText(),
                "no declared response content = honest empty body, never invented data");
    }

    @Test
    void sameSpecMapsByteIdenticallyEveryRun() {
        String first = drafts().get(0).responseJson();
        String second = drafts().get(0).responseJson();
        assertEquals(first, second,
                "seeds must derive from the field identity, not the clock");
        assertNotEquals(first, drafts().get(1).responseJson());
    }

    @Test
    void longTitleClampsToColumnNameWidth() {
        String api = """
                {"openapi":"3.0.1","info":{"title":"t","version":"1"},
                 "paths":{"/x":{"get":{"summary":"%s",
                  "responses":{"200":{"description":"ok",
                   "content":{"application/json":{"schema":{"type":"object"}}}}}}}}}
                """.formatted("订单".repeat(80));
        ParsedApiSpec parsed = parser.parse(
                api.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        MockStubDraft draft = mapper.fromParsedSpec(1L, parsed).get(0);
        assertEquals(120, draft.name().length(), "clamped, not rejected at save time");
    }
}
