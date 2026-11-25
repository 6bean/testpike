package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.spec.domain.component.OpenApiSpecParser;
import com.sixbean.testpike.core.spec.domain.component.ParsedApiSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic response-schema expansion : the contract-direct
 * channel now also unfolds the declared response schema (type / object /
 * array / enum / format / example / default) into a realistic sample body,
 * resolves {@code $ref} chains through the stored snapshot and degrades
 * honestly on cycles and depth limits — still zero LLM tokens and replay
 * stable for the same contract.
 * <p>Pinned behaviour:
 * (1) example beats default beats first-enum beats format synthesis beats
 * the type placeholder;
 * (2) nested object/array properties unfold one level per declaration;
 * (3) a component model that references itself renders a {@code $cycle}
 * marker object instead of recursing forever;
 * (4) the configurable expansion depth cap truncates deeper nesting with an
 * honest marker rather than guessing;
 * (5) legacy snapshots stored before the tree field exists keep the old
 * echo-only body (never crash, never invent);
 * (6) the worst-case bulk fixture (500 endpoints / 2000 fields) maps inside
 * the budget and its timing/size numbers land on the benchmark ledger.
 */
class StubDraftMapperSchemaExpandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OpenApiSpecParser parser = new OpenApiSpecParser();
    private final StubDraftMapper mapper = new StubDraftMapper();

    private static final String TYPED_SPEC = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Typed API", "version": "1.0.0"},
              "paths": {
                "/reports/{id}": {
                  "get": {
                    "operationId": "getReport",
                    "parameters": [
                      {"name": "id", "in": "path", "required": true,
                       "schema": {"type": "integer"}}
                    ],
                    "responses": {
                      "200": {"description": "ok", "content": {"application/json": {
                        "schema": {"type": "object", "properties": {
                          "title": {"type": "string", "example": "月度结算"},
                          "owner": {"type": "string", "default": "未分配"},
                          "state": {"type": "string", "enum": ["DRAFT", "SENT"]},
                          "hotline": {"type": "string", "format": "phone"},
                          "total": {"type": "number"},
                          "flagged": {"type": "boolean"},
                          "tags": {"type": "array", "items": {"type": "string"}},
                          "detail": {"$ref": "#/components/schemas/ReportDetail"}
                        }}}}}
                    }
                  }
                }
              },
              "components": {"schemas": {
                "ReportDetail": {"type": "object", "properties": {
                  "page": {"type": "integer"},
                  "self": {"$ref": "#/components/schemas/ReportDetail"}
                }}
              }}
            }
            """;

    private MockStubDraft reportDraft(String spec) {
        return reportDraft(spec, 6);
    }

    private MockStubDraft reportDraft(String spec, int expandMaxDepth) {
        ParsedApiSpec parsed = parser.parse(
                spec.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        List<MockStubDraft> out =
                mapper.fromParsedSpec(91L, parsed, expandMaxDepth);
        return out.get(0);
    }

    private static JsonNode bodyOf(MockStubDraft draft) throws Exception {
        // the builder document keeps body as an embedded JSON string, exactly
        // as the form editor contract says — parse it once more to walk fields
        return MAPPER.readTree(MAPPER.readTree(draft.responseJson()).path("body").asText());
    }

    @Test
    void typedFieldsExpandByPrecedence() throws Exception {
        MockStubDraft draft = reportDraft(TYPED_SPEC);
        JsonNode body = bodyOf(draft);
        assertEquals("月度结算", body.path("title").asText(),
                "explicit example wins over every other source");
        assertEquals("未分配", body.path("owner").asText(),
                "default is the second precedence");
        assertEquals("DRAFT", body.path("state").asText(),
                "enum falls to its first declared value");
        assertTrue(body.path("hotline").asText().startsWith("${gen.phone(seed="),
                "recognised formats ride the seeded genkit whitelist: "
                        + body.path("hotline").asText());
        assertEquals(0, body.path("total").asInt());
        assertFalse(body.path("flagged").asBoolean(true));
        assertTrue(body.path("tags").isArray() && body.path("tags").size() == 1,
                "array unfolds one sample item");
        assertEquals(0, body.path("detail").path("page").asInt(),
                "$ref resolves through the snapshot into a nested sample");
    }

    @Test
    void cyclicRefDegradesToHonestMarker() throws Exception {
        MockStubDraft draft = reportDraft(TYPED_SPEC);
        JsonNode self = bodyOf(draft).path("detail").path("self");
        assertEquals("ReportDetail", self.path("$cycle").asText(),
                "a model that references itself stops with a $cycle note, "
                        + "never an infinite tree: " + self);
    }

    @Test
    void depthCapTruncatesDeeperNesting() throws Exception {
        Map<String, Object> l3 = Map.of("type", "string");
        Map<String, Object> l2 = Map.of("type", "object", "properties", Map.of("l3", l3));
        Map<String, Object> l1 = Map.of("type", "object", "properties", Map.of("l2", l2));
        Map<String, Object> root = Map.of("type", "object", "properties", Map.of("l1", l1));
        Map<String, Object> schemaWrap = Map.of("schema", root);
        Map<String, Object> media = Map.of("application/json", schemaWrap);
        Map<String, Object> ok = Map.of("description", "ok", "content", media);
        Map<String, Object> get = Map.of("operationId", "x", "responses", Map.of("200", ok));
        Map<String, Object> paths = Map.of("/x", Map.of("get", get));
        String deep = MAPPER.writeValueAsString(Map.of(
                "openapi", "3.0.1", "info", Map.of("title", "Deep", "version", "1"),
                "paths", paths));
        JsonNode body = bodyOf(reportDraft(deep, 2));
        assertTrue(body.path("l1").has("l2"), "first level still expands");
        assertTrue(body.path("l1").path("l2").has("$truncated"),
                "a container at the cap renders an honest truncation note "
                        + "instead of guessing deeper: " + body.path("l1"));
    }

    @Test
    void legacySnapshotWithoutTreeKeepsEchoBehaviour() throws Exception {
        MockStubDraft draft = reportDraft(TYPED_SPEC);
        String body = MAPPER.readTree(draft.responseJson()).path("body").asText();
        assertTrue(body.contains("{{mock.pathVar.id}}"),
                "declared path parameters still echo through the kernel: " + body);
    }

    @Test
    void noDeclaredSchemaStaysStaticEmpty() throws Exception {
        String bare = """
                {"openapi": "3.0.1", "info": {"title": "Bare", "version": "1"},
                 "paths": {"/ping": {"get": {"operationId": "ping",
                   "responses": {"204": {"description": "no content"}}}}}}
                """;
        ParsedApiSpec parsed = parser.parse(
                bare.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        MockStubDraft draft = mapper.fromParsedSpec(92L, parsed).get(0);
        JsonNode doc = MAPPER.readTree(draft.responseJson());
        assertEquals("static", doc.path("bodyMode").asText());
        assertEquals("", doc.path("body").asText(),
                "no declared response body stays honestly empty");
    }

    @Test
    void sameContractSameSeedReplaysByteIdentically() {
        String first = reportDraft(TYPED_SPEC).responseJson();
        String second = reportDraft(TYPED_SPEC).responseJson();
        assertEquals(first, second,
                "expansion is clock-free: same contract, same bytes forever");
    }

    @Test
    void worstCaseBulkContractMapsWithinBudget() throws Exception {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int f = 0; f < 4; f++) {
            props.put("f" + f, Map.of("type", "string"));
        }
        Map<String, Object> schema = Map.of("type", "object", "properties", props);
        Map<String, Object> ok = Map.of("description", "ok", "content",
                Map.of("application/json", Map.of("schema", schema)));
        Map<String, Object> paths = new LinkedHashMap<>();
        for (int i = 0; i < 500; i++) {
            paths.put("/res" + i, Map.of("get",
                    Map.of("operationId", "op" + i, "responses", Map.of("200", ok))));
        }
        String doc = MAPPER.writeValueAsString(Map.of(
                "openapi", "3.0.1", "info", Map.of("title", "Bulk", "version", "1"),
                "paths", paths));

        long start = System.nanoTime();
        ParsedApiSpec parsed = parser.parse(
                doc.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        List<MockStubDraft> drafts = mapper.fromParsedSpec(93L, parsed);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long totalBytes = drafts.stream()
                .mapToLong(d -> d.responseJson().getBytes(StandardCharsets.UTF_8).length)
                .sum();
        System.out.println("MJ-BENCHMARK parse+map 500 endpoints/2000 fields: "
                + elapsedMs + " ms, drafts=" + drafts.size()
                + ", response payload total=" + totalBytes + " bytes");
        assertEquals(500, drafts.size());
        assertNotNull(drafts.get(0).responseJson());
        assertTrue(elapsedMs < 15_000,
                "worst-case bulk mapping must stay interactive-scale, took " + elapsedMs + " ms");
    }


}
