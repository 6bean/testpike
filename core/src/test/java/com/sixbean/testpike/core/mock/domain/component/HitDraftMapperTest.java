package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.mock.api.dto.MockHitInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inspector row → save-ready stub draft mapping the
 * "make this request a stub" one-click).
 * <p>Contract the mapper must keep:
 * <ul>
 * <li>one exact-path draft per answered ledger row: method + path with the
 * query facet stripped (a stub pinned to one captured query string would
 * miss the next real call — same honesty rule as the cassette strict plan);</li>
 * <li>provenance = record + hit:{id} so a promoted rule retraces to the exact
 * traffic observation it was built from;</li>
 * <li>the desensitizer placeholder ({@code ***}) never rides into a stub as a
 * fake credential and content-length is dropped (the captured body may have
 * been truncated by the size gate) — shared gate with the cassette mapper,
 * not a second copy;</li>
 * <li>rows with no response plane (transport failure / unanswered miss) yield
 * null — there is nothing recorded to promote, and inventing a body would be
 * a dishonest draft;</li>
 * <li>forwarded miss rows (matched=false but a real upstream answer) ARE
 * promotable — capturing what the upstream actually answered is exactly the
 * point of the one-click.</li>
 * </ul>
 */
class HitDraftMapperTest {

    private final HitDraftMapper mapper = new HitDraftMapper();
    private final ObjectMapper json = new ObjectMapper();

    private MockHitInfo row(long id, String method, String path, Integer status,
                            Map<String, Object> responseHeaders, String body) {
        return row(id, method, path, status, responseHeaders, body, true);
    }

    private MockHitInfo row(long id, String method, String path, Integer status,
                            Map<String, Object> responseHeaders, String body, boolean matched) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("method", method);
        request.put("path", path);
        request.put("query", Map.of("page", "2"));
        request.put("headers", Map.of("authorization", "***"));
        request.put("body", null);
        request.put("stubName", matched ? "既有规则" : null);
        Map<String, Object> response = status == null ? Map.of()
                : Map.of("status", status,
                        "headers", responseHeaders == null ? Map.of() : responseHeaders,
                        "body", body == null ? "" : body);
        return new MockHitInfo(id, 3L, matched ? 11L : null, request, response, matched,
                List.of(), 12, null, null, LocalDateTime.now(), null);
    }

    @Test
    void answeredRowMapsToOneExactDraftRetracingToTheHit() throws Exception {
        MockStubDraft draft = mapper.toDraft(row(9L, "GET", "/orders/42", 200,
                Map.of("content-type", "application/json"), "{\"ok\":true}", true));

        assertNotNull(draft, "an answered row must produce a draft");
        assertEquals("GET /orders/42", draft.endpointKey());
        assertEquals("GET", draft.method());
        assertEquals("exact", draft.pathMatchType());
        assertEquals("/orders/42", draft.pathPattern());
        assertEquals("[]", draft.matchersJson());
        assertEquals("record", draft.source());
        assertEquals("hit:9", draft.sourceRef());
        assertTrue(draft.name().contains("GET /orders/42"),
                "rule name should retrace the observed call, got: " + draft.name());

        JsonNode doc = json.readTree(draft.responseJson());
        assertEquals(200, doc.get("status").asInt());
        assertEquals("static", doc.get("bodyMode").asText());
        assertEquals("{\"ok\":true}", doc.get("body").asText(),
                "the recorded body IS the truth — no template invention");
        assertEquals("application/json", doc.get("headers").get("content-type").asText());
    }

    @Test
    void queryFacetIsStrippedFromThePromotedPath() {
        MockStubDraft draft = mapper.toDraft(row(10L, "POST", "/checkout?token=***", 201,
                Map.of(), "created", false));

        assertNotNull(draft, "a forwarded miss row with a real answer is promotable");
        assertEquals("/checkout", draft.pathPattern());
        assertEquals("POST /checkout", draft.endpointKey());
    }

    @Test
    void maskedSentinelAndContentLengthNeverRideIntoTheDraft() throws Exception {
        MockStubDraft draft = mapper.toDraft(row(11L, "GET", "/health", 200,
                Map.of("authorization", "***", "content-length", "5678",
                        "x-trace", "keep-me"), "pong"));

        JsonNode headers = json.readTree(draft.responseJson()).get("headers");
        assertNull(headers.get("authorization"),
                "the desensitizer placeholder must never become a fake credential");
        assertNull(headers.get("content-length"),
                "a size-gated body may contradict a copied content-length");
        assertEquals("keep-me", headers.get("x-trace").asText());
    }

    @Test
    void rowWithoutResponsePlaneHasNothingToPromote() {
        assertNull(mapper.toDraft(row(12L, "GET", "/orders/1", null, null, null, false)),
                "unanswered rows must be refused by the caller, not drafted with an invented body");
    }

    @Test
    void missingMethodOrPathRefusesQuietly() {
        Map<String, Object> response = Map.of("status", 200, "headers", Map.of(), "body", "");
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("method", null);
        request.put("path", "/x");
        MockHitInfo broken = new MockHitInfo(13L, 3L, null,
                request, response, false,
                List.of(), null, null, null, LocalDateTime.now(), null);
        assertNull(mapper.toDraft(broken));
    }
}
