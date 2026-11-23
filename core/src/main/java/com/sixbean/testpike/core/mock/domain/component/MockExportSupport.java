package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared reading side of the dialect exporters . Both renderers
 * need the same facts — the routing root of a space, the parsed response
 * builder payload, the parsed matcher rows — and both must collect the same
 * style of honesty notes naming the stub. Keeping the parsing here once
 * prevents the two dialects from drifting on what a rule "means".
 * <p>Composition only (no inheritance): renderers call these helpers.
 */
public final class MockExportSupport {

    /** One JSON tree for the whole module — parse-only helpers, zero state. */
    static final ObjectMapper MAPPER = new ObjectMapper();

    private MockExportSupport() {
    }

    /** Routing root every exported path must carry to be callable as-is. */
    static String routeRoot(MockSpaceInfo space) {
        return "/mocks/" + space.spaceKey();
    }

    /** Parsed response builder payload; malformed text degrades to an empty object. */
    static JsonNode readResponse(MockStubInfo stub) {
        return readTree(stub.responseJson(), MAPPER.createObjectNode());
    }

    /** Parsed matcher rows; malformed text degrades to an empty array. */
    static List<JsonNode> matcherRows(MockStubInfo stub) {
        List<JsonNode> rows = new ArrayList<>();
        JsonNode node = readTree(stub.matchersJson(), MAPPER.createArrayNode());
        if (node.isArray()) {
            node.forEach(rows::add);
        }
        return rows;
    }

    /** Request headers map from the response payload (insertion order kept). */
    static Map<String, String> responseHeaders(JsonNode response) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode headers = response.path("headers");
        if (headers.isObject()) {
            headers.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
        }
        return out;
    }

    /**
     * Body text plus its mode: template-mode bodies keep their placeholder
     * text verbatim (never dropped) — callers add a note because neither
     * dialect can run our genkit placeholders.
     */
    static String bodyText(JsonNode response) {
        return response.path("body").asText("");
    }

    static boolean isTemplateBody(JsonNode response) {
        return "template".equals(response.path("bodyMode").asText("static"));
    }

    /** True when the payload declares a JSON content type and the body parses. */
    static boolean bodyIsJson(JsonNode response, String body) {
        String ct = response.path("contentType").asText(
                responseHeaders(response).getOrDefault("Content-Type", ""));
        return ct.contains("json") && readsAsTree(body);
    }

    static boolean readsAsTree(String text) {
        try {
            MAPPER.readTree(text);
            return text.startsWith("{") || text.startsWith("[");
        } catch (Exception e) {
            return false;
        }
    }

    static JsonNode readTree(String text, JsonNode fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Matcher-row honesty: which rows the target dialect cannot carry.
     * WireMock expresses query and header rows natively; Mockoon carries
     * neither faithfully, and cookie/body rows are refused in both (never a
     * silent drop — every skipped row becomes a named note).
     */
    static List<String> unsupportedMatcherKinds(List<JsonNode> rows, boolean wireMock) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode row : rows) {
            String kind = row.path("kind").asText("?");
            if (wireMock && ("query".equals(kind) || "header".equals(kind))) {
                continue;
            }
            if (!kinds.contains(kind)) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    static ArrayNode headersNode(Map<String, String> headers) {
        ArrayNode arr = MAPPER.createArrayNode();
        headers.forEach((k, v) -> {
            ObjectNode h = arr.addObject();
            h.put("name", k);
            h.put("value", v);
        });
        return arr;
    }

    static ObjectNode headersObject(Map<String, String> headers) {
        ObjectNode obj = MAPPER.createObjectNode();
        headers.forEach(obj::put);
        return obj;
    }

    /** Path text without the routing root, per path type; notes what a dialect cannot say. */
    static String relativePath(MockStubInfo stub, List<String> notes, String dialect) {
        String pattern = stub.pathPattern() == null ? "/" : stub.pathPattern();
        String type = stub.pathMatchType() == null ? "exact" : stub.pathMatchType();
        return switch (type) {
            case "exact" -> pattern;
            case "prefix" -> pattern.endsWith("/") ? pattern + "*" : pattern + "*";
            case "template" -> pattern.replaceAll("\\{([A-Za-z0-9_]+)}",
                    dialect.equals("mockoon") ? ":$1" : "[^/]+");
            default -> {
                notes.add("「" + stub.name() + "」path 型 " + type
                        + "（" + dialect + " 方言不能原生表达，路径按字面导出，导入后需人工复核）");
                yield pattern;
            }
        };
    }
}
