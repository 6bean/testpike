package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.common.domain.component.FirstSuccessCode;
import com.sixbean.testpike.core.common.domain.component.FormatGenSample;
import com.sixbean.testpike.core.spec.domain.component.ApiEndpoint;
import com.sixbean.testpike.core.spec.domain.component.ApiParameter;
import com.sixbean.testpike.core.spec.domain.component.ParsedApiSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Channel-A contract-direct stub mapping (: tp_api_spec endpoints →
 * save-ready stub drafts, zero LLM tokens — SpecDraftMapper's twin on the
 * mock side, same determinism floor).
 * <p>What the draft carries and why:
 * <ul>
 * <li>verbs the traffic kernel cannot serve (anything outside ANY/GET/POST/
 * PUT/DELETE/PATCH/HEAD/OPTIONS) are dropped — mapping a rule the mock-server
 * would never match is a dishonest draft;</li>
 * <li>a path with {@code {var}} captures maps to match type template so the
 * stub matches the same URL shape the contract declares; plain paths stay
 * exact;</li>
 * <li>the response document follows the form-builder contract (status/headers/
 * bodyMode/body): status = lowest declared 2xx via the shared success-code
 * pick; when the stored snapshot carries an inlined response schema tree the
 * tree unfolds into a sample body (example &gt; default &gt; first enum &gt;
 * seeded format synthesis &gt; type placeholder), with {@code $cycle} /
 * {@code $truncated} markers where the contract loops or outgrows the depth
 * cap; path and query parameters echo through the template kernel
 * ({{mock.pathVar.x}}/{{mock.query.x}}); a response without declared content
 * stays static-empty instead of inventing a body;</li>
 * <li>provenance is stamped openapi + spec:{id} so the stale badge chain can
 * retrace the origin contract of every generated rule.</li>
 * </ul>
 * A draft is a proposal: it goes through the normal stub save chain, so
 * MockRuleValidator remains the single validation point (no bypass).
 */
public class StubDraftMapper {

    /** Verb spectrum of the match kernel (MockRuleValidator.METHODS twin). */
    private static final Set<String> SERVED_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /**
     * Fallback expansion depth when the caller has no configured value —
     * the shipped default of the hot-update key
     * mock.spec-import.schema-expand-max-depth (6), kept in one place so the
     * service and every direct consumer agree without a second table.
     */
    public static final int DEFAULT_EXPAND_MAX_DEPTH = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Endpoint list → stub drafts (parser order kept; unserved verbs dropped),
     * at the shipped default expansion depth.
     *
     * @param specId provenance anchor of the contract snapshot (spec:{id})
     */
    public List<MockStubDraft> fromParsedSpec(long specId, ParsedApiSpec parsed) {
        return fromParsedSpec(specId, parsed, DEFAULT_EXPAND_MAX_DEPTH);
    }

    /**
     * Same mapping with an explicit schema-expansion depth cap (the caller
     * reads the hot-update configuration key so the limit is tunable without
     * a restart).
     */
    public List<MockStubDraft> fromParsedSpec(long specId, ParsedApiSpec parsed,
                                              int expandMaxDepth) {
        List<MockStubDraft> out = new ArrayList<>();
        int cap = Math.max(1, expandMaxDepth);
        for (ApiEndpoint ep : parsed.endpoints()) {
            if (!SERVED_METHODS.contains(ep.method().toUpperCase(Locale.ROOT))) {
                continue;
            }
            out.add(toDraft(specId, ep, cap));
        }
        return out;
    }

    private MockStubDraft toDraft(long specId, ApiEndpoint ep, int expandMaxDepth) {
        String path = ep.path();
        boolean templated = path.indexOf('{') >= 0;
        return new MockStubDraft(
                ep.endpointKey(),
                MockStubDraft.clampName(ep.title()),
                ep.method().toUpperCase(Locale.ROOT),
                templated ? "template" : "exact",
                path,
                "[]",
                responseDocument(ep, expandMaxDepth),
                MockStubDraft.SOURCE_OPENAPI,
                "spec:" + specId);
    }

    /**
     * Response builder document per the F3 contract. Echo references ride the
     * single template kernel; gen calls carry explicit stable seeds so the
     * same contract always renders the same sample bodies.
     */
    private String responseDocument(ApiEndpoint ep, int expandMaxDepth) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("status", FirstSuccessCode.pick(ep.responseSchemas()));
        doc.put("headers", Map.of("Content-Type", "application/json"));
        boolean hasContentSchema = !ep.responseSchemas().isEmpty();
        doc.put("bodyMode", hasContentSchema ? "template" : "static");
        doc.put("body", hasContentSchema ? sampleBody(ep, expandMaxDepth) : "");
        return toJson(doc);
    }

    /**
     * Sample body = the inlined response schema unfolded into typed samples
     * (when the snapshot carries a tree for the picked success code), plus
     * the path/query echoes through the template kernel and seeded
     * ${gen.…} samples for format-recognised parameters. Seeds derive from
     * endpointKey|name|location — stable identity, never the clock (replay
     * determinism, seed events stay auditable).
     */
    private String sampleBody(ApiEndpoint ep, int expandMaxDepth) {
        Map<String, Object> body = new LinkedHashMap<>();
        JsonNode tree = schemaTreeFor(ep, FirstSuccessCode.pickKey(ep.responseSchemas()));
        if (tree != null) {
            Object unfolded = sample(tree, ep.endpointKey(), 0, expandMaxDepth);
            if (unfolded instanceof Map) {
                body.putAll(castMap(unfolded));
            } else if (unfolded != null) {
                body.put("result", unfolded);
            }
        }
        for (ApiParameter p : ep.parameters()) {
            if ("path".equals(p.location())) {
                body.put(p.name(), "{{mock.pathVar." + p.name() + "}}");
            } else if ("query".equals(p.location())) {
                body.put(p.name(), "{{mock.query." + p.name() + "}}");
            }
            String fn = FormatGenSample.functionFor(p.format());
            if (fn != null) {
                long seed = FormatGenSample.seedFrom(ep.endpointKey() + "|" + p.name()
                        + "|" + p.location());
                body.putIfAbsent(p.name() + "Sample",
                        "${gen." + fn + "(seed=" + seed + ")}");
            }
        }
        if (body.isEmpty()) {
            body.put("code", 0);
            body.put("message", "ok");
        }
        return toJson(body);
    }

    /** Parsed inlined tree of the picked success code, null on legacy rows. */
    private JsonNode schemaTreeFor(ApiEndpoint ep, String codeKey) {
        Map<String, String> trees = ep.responseSchemaTrees();
        if (trees == null || codeKey == null) {
            return null;
        }
        String raw = trees.get(codeKey);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            // a damaged tree must never sink the draft: fall back to echo-only
            return null;
        }
    }

    /**
     * One schema node → one deterministic sample value. Precedence:
     * example, default, first enum, recognised format, type placeholder.
     * Honest markers from the inliner ($cycle/$unresolved/$truncated) pass
     * through untouched so the draft states exactly where the contract ran
     * out of shape.
     */
    private Object sample(JsonNode schema, String identity, int depth, int cap) {
        if (schema == null || !schema.isObject()) {
            return "";
        }
        for (String marker : List.of("$cycle", "$unresolved", "$truncated")) {
            if (schema.has(marker)) {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put(marker, schema.get(marker).asText());
                return note;
            }
        }
        if (schema.has("example")) {
            return value(schema.get("example"));
        }
        if (schema.has("default")) {
            return value(schema.get("default"));
        }
        JsonNode enumValues = schema.path("enum");
        if (enumValues.isArray() && !enumValues.isEmpty()) {
            return value(enumValues.get(0));
        }
        String formatFn = FormatGenSample.functionFor(schema.path("format").asText(null));
        String type = schema.path("type").asText(null);
        if ("string".equals(type) && formatFn != null) {
            long seed = FormatGenSample.seedFrom(identity);
            return "${gen." + formatFn + "(seed=" + seed + ")}";
        }
        if ("object".equals(type)) {
            if (depth >= cap) {
                return Map.of("$truncated", "depth");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            JsonNode props = schema.path("properties");
            props.fieldNames().forEachRemaining(name ->
                    out.put(name, sample(props.get(name), identity + "." + name,
                            depth + 1, cap)));
            return out;
        }
        if ("array".equals(type)) {
            if (depth >= cap) {
                return List.of(Map.of("$truncated", "depth"));
            }
            JsonNode items = schema.path("items");
            List<Object> out = new ArrayList<>();
            out.add(items.isObject()
                    ? sample(items, identity + "[]", depth + 1, cap) : "");
            return out;
        }
        if ("boolean".equals(type)) {
            return false;
        }
        if ("integer".equals(type) || "number".equals(type)) {
            return 0;
        }
        return "";
    }

    /** Concrete example/default/enum node → plain Java JSON value. */
    private Object value(JsonNode node) {
        try {
            return MAPPER.treeToValue(node, Object.class);
        } catch (Exception e) {
            return node.asText();
        }
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("stub draft serialization failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object node) {
        return (Map<String, Object>) node;
    }
}
