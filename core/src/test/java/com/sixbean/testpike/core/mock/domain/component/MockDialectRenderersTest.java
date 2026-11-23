package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialect renderer contracts : the two
 * renderers must produce files that Mockoon and WireMock can actually load,
 * must carry the lossless notes honestly, and must never let an unsupported
 * matcher silently vanish from the export.
 */
class MockDialectRenderersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MockSpaceInfo space() {
        return new MockSpaceInfo(7L, 1L, "Checkout mocks", "ck3y", null, null,
                "prefix", 1, "none", null, null, null, 100, 0, null, 3,
                LocalDateTime.of(2026, 10, 16, 0, 0, 0), LocalDateTime.of(2026, 10, 16, 0, 0, 0),
                null, null);
    }

    private static MockStubInfo stub(String name, String method, String pathType, String path,
                                     String matchers, String response) {
        return new MockStubInfo(1L, 7L, name, 10, method, pathType, path,
                matchers, response, null, null, null, "manual", null, true,
                LocalDateTime.of(2026, 10, 16, 0, 0, 0), LocalDateTime.of(2026, 10, 16, 0, 0, 0));
    }

    private static final String OK_JSON_RESPONSE = """
            {"status":201,"headers":{"X-TP-Test":"1","Content-Type":"application/json"},\
            "bodyMode":"static","body":"{\\"ok\\":true}"}""";

    @Test
    void mockoon_carriesEndpointsUnderSpaceRoute_withStatusHeadersAndDisabledFlag() throws Exception {
        List<MockStubInfo> stubs = List.of(
                stub("create order", "POST", "exact", "/orders", "[]", OK_JSON_RESPONSE),
                new MockStubInfo(2L, 7L, "off rule", 20, "GET", "exact", "/off", "[]", OK_JSON_RESPONSE,
                        null, null, null, "manual", null, false,
                        LocalDateTime.now(), LocalDateTime.now()));
        String json = new MockoonDialectRenderer().render(space(), stubs);
        JsonNode root = MAPPER.readTree(json);
        JsonNode endpoints = root.path("endpoints");
        assertEquals(2, endpoints.size());
        JsonNode first = endpoints.get(0);
        assertEquals("post", first.path("method").asText(), "Mockoon stores verbs lowercase");
        assertEquals("/mocks/ck3y/orders", first.path("path").asText());
        assertEquals(201, first.path("response").get(0).path("status").asInt());
        JsonNode headers = first.path("response").get(0).path("headers");
        assertTrue(headers.toString().contains("X-TP-Test"));
        assertTrue(headers.toString().contains("application/json"));
        assertEquals("{\"ok\":true}", first.path("response").get(0).path("body").asText());
        assertTrue(endpoints.get(1).path("isDisabled").asBoolean(),
                "disabled stub must export disabled, never dropped");
    }

    @Test
    void mockoon_pathTypesMapToNativeSyntax_orFallIntoRegexWithNote() throws Exception {
        List<MockStubInfo> stubs = List.of(
                stub("prefix rule", "GET", "prefix", "/files", "[]", OK_JSON_RESPONSE),
                stub("template rule", "GET", "template", "/users/{id}", "[]", OK_JSON_RESPONSE),
                stub("regex rule", "GET", "regex", "/x.*", "[]", OK_JSON_RESPONSE));
        ExportDialectResult result = new MockoonDialectRenderer().renderResult(space(), stubs);
        JsonNode root = MAPPER.readTree(result.content());
        JsonNode endpoints = root.path("endpoints");
        assertEquals("/mocks/ck3y/files*", endpoints.get(0).path("path").asText());
        assertEquals("/mocks/ck3y/users/:id", endpoints.get(1).path("path").asText());
        // Mockoon paths are not regex engines: a regex rule must keep its body
        // and leave an honest note instead of exporting a lying literal path.
        assertTrue(endpoints.get(2).path("path").asText().length() > 0);
        assertTrue(result.notes().stream().anyMatch(n -> n.contains("regex")),
                "regex path must appear in the notes: " + result.notes());
    }

    @Test
    void wiremock_exactUsesUrl_prefixedTemplateAndRegexUseUrlPathPattern() throws Exception {
        List<MockStubInfo> stubs = List.of(
                stub("exact", "GET", "exact", "/a", "[]", OK_JSON_RESPONSE),
                stub("prefix", "GET", "prefix", "/b", "[]", OK_JSON_RESPONSE),
                stub("template", "GET", "template", "/c/{id}", "[]", OK_JSON_RESPONSE),
                stub("regex", "GET", "regex", "/d.*", "[]", OK_JSON_RESPONSE));
        String json = new WireMockDialectRenderer().render(space(), stubs);
        JsonNode mappings = MAPPER.readTree(json).path("mappings");
        assertEquals(4, mappings.size());
        JsonNode r0 = mappings.get(0).path("request");
        assertEquals("/mocks/ck3y/a", r0.path("url").asText());
        assertFalse(r0.has("urlPathPattern"), "exact must not be smuggled as a pattern");
        assertTrue(mappings.get(1).path("request").path("urlPathPattern").asText().startsWith("\\Q/b\\E")
                        || mappings.get(1).path("request").path("urlPathPattern").asText().endsWith(".*"),
                "prefix maps to a regex pattern");
        assertTrue(mappings.get(2).path("request").path("urlPathPattern").asText().contains("[^/]+"),
                "template {id} must become a capture segment");
        assertEquals("/mocks/ck3y/d.*", mappings.get(3).path("request").path("urlPathPattern").asText());
    }

    @Test
    void wiremock_jsonBodyGoesToJsonBodyAndTextBodyStaysString() throws Exception {
        String jsonTextResponse = "{\"contentType\":\"application/json\",\"status\":200,"
                + "\"headers\":{},\"bodyMode\":\"static\",\"body\":\"{\\\"a\\\":1}\"}";
        String json = new WireMockDialectRenderer().render(space(),
                List.of(stub("json", "GET", "exact", "/j", "[]", jsonTextResponse)));
        JsonNode response = MAPPER.readTree(json).path("mappings").get(0).path("response");
        assertEquals(1, response.path("jsonBody").path("a").asInt());
        assertFalse(response.has("body"), "json body must not double out as raw text");
    }

    @Test
    void templateBodyIsKeptWithNote_bothDialects() throws Exception {
        String templated = "{\"status\":200,\"headers\":{},\"bodyMode\":\"template\",\"body\":\"id={{mock.path.id}}\"}";
        List<MockStubInfo> stubs = List.of(stub("tpl", "GET", "exact", "/t", "[]", templated));
        ExportDialectResult mk = new MockoonDialectRenderer().renderResult(space(), stubs);
        ExportDialectResult wm = new WireMockDialectRenderer().renderResult(space(), stubs);
        assertTrue(mk.notes().stream().anyMatch(n -> n.contains("tpl")));
        assertTrue(wm.notes().stream().anyMatch(n -> n.contains("tpl")));
        assertTrue(mk.content().contains("{{mock.path.id}}"), "body must be kept, never dropped");
        assertTrue(wm.content().contains("{{mock.path.id}}"));
    }

    @Test
    void unsupportedMatcherRows_areNotedWithStubName_neverSilentlyDropped() throws Exception {
        String cookieRow = "[{\"kind\":\"cookie\",\"key\":\"sid\",\"matchType\":\"exact\",\"value\":\"s1\"}]";
        List<MockStubInfo> stubs = List.of(stub("cookie rule", "GET", "exact", "/c", cookieRow, OK_JSON_RESPONSE));
        ExportDialectResult mk = new MockoonDialectRenderer().renderResult(space(), stubs);
        ExportDialectResult wm = new WireMockDialectRenderer().renderResult(space(), stubs);
        assertTrue(mk.notes().stream().anyMatch(n -> n.contains("cookie rule") && n.contains("cookie")));
        assertTrue(wm.notes().stream().anyMatch(n -> n.contains("cookie rule") && n.contains("cookie")));
    }

    @Test
    void anyMethod_exportedWildcardOrNoted() throws Exception {
        List<MockStubInfo> stubs = List.of(stub("wild", "ANY", "exact", "/w", "[]", OK_JSON_RESPONSE));
        String wm = new WireMockDialectRenderer().render(space(), stubs);
        JsonNode request = MAPPER.readTree(wm).path("mappings").get(0).path("request");
        // WireMock matches all methods when the method key is absent; Mockoon has "all".
        assertFalse(request.path("method").asText("").equals("ANY"), "ANY must not leak as a fake verb");
        String mk = new MockoonDialectRenderer().render(space(), stubs);
        assertEquals("all", MAPPER.readTree(mk).path("endpoints").get(0).path("method").asText());
    }
}
