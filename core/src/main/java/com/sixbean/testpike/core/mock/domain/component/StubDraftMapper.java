package com.sixbean.testpike.core.mock.domain.component;

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
 * pick; path and query parameters echo through the template kernel
 * ({{mock.pathVar.x}}/{{mock.query.x}}); format-recognised fields synthesise
 * Chinese-domain samples through the seeded ${gen.…} whitelist (phone/idCard/
 * … — the genkit assets, same table the request import reads); a response
 * without declared content stays static-empty instead of inventing a body;</li>
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Endpoint list → stub drafts (parser order kept; unserved verbs dropped).
     *
     * @param specId provenance anchor of the contract snapshot (spec:{id})
     */
    public List<MockStubDraft> fromParsedSpec(long specId, ParsedApiSpec parsed) {
        List<MockStubDraft> out = new ArrayList<>();
        for (ApiEndpoint ep : parsed.endpoints()) {
            if (!SERVED_METHODS.contains(ep.method().toUpperCase(Locale.ROOT))) {
                continue;
            }
            out.add(toDraft(specId, ep));
        }
        return out;
    }

    private MockStubDraft toDraft(long specId, ApiEndpoint ep) {
        String path = ep.path();
        boolean templated = path.indexOf('{') >= 0;
        return new MockStubDraft(
                ep.endpointKey(),
                MockStubDraft.clampName(ep.title()),
                ep.method().toUpperCase(Locale.ROOT),
                templated ? "template" : "exact",
                path,
                "[]",
                responseDocument(ep),
                MockStubDraft.SOURCE_OPENAPI,
                "spec:" + specId);
    }

    /**
     * Response builder document per the F3 contract. Echo references ride the
     * single template kernel; gen calls carry explicit stable seeds so the
     * same contract always renders the same sample bodies.
     */
    private String responseDocument(ApiEndpoint ep) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("status", FirstSuccessCode.pick(ep.responseSchemas()));
        doc.put("headers", Map.of("Content-Type", "application/json"));
        boolean hasContentSchema = !ep.responseSchemas().isEmpty();
        doc.put("bodyMode", hasContentSchema ? "template" : "static");
        doc.put("body", hasContentSchema ? sampleBody(ep) : "");
        return toJson(doc);
    }

    /**
     * Sample body = object echoing declared path/query fields and, for
     * content-declaring endpoints, format-synthesised fields keyed by
     * parameter name. Seeds = endpointKey|name|location — stable identity,
     * never the clock (replay determinism, seed events stay auditable).
     */
    private String sampleBody(ApiEndpoint ep) {
        List<String> fields = new ArrayList<>();
        for (ApiParameter p : ep.parameters()) {
            if ("path".equals(p.location())) {
                fields.add("\"" + p.name() + "\": \"{{mock.pathVar." + p.name() + "}}\"");
            } else if ("query".equals(p.location())) {
                fields.add("\"" + p.name() + "\": \"{{mock.query." + p.name() + "}}\"");
            }
            String fn = FormatGenSample.functionFor(p.format());
            if (fn != null) {
                long seed = FormatGenSample.seedFrom(ep.endpointKey() + "|" + p.name()
                        + "|" + p.location());
                fields.add("\"" + p.name() + "Sample\": \"${gen." + fn + "(seed=" + seed + ")}\"");
            }
        }
        if (fields.isEmpty()) {
            return "{\"code\": 0, \"message\": \"ok\"}";
        }
        return "{" + String.join(", ", fields) + "}";
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("stub draft serialization failed", e);
        }
    }
}
