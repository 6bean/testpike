package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cassette frame → stub draft mapping for playback .
 * <p>Two replay shapes, one honesty floor:
 * <ul>
 * <li>strict — one exact-match draft per distinct recorded URL (method + raw
 * path, query stripped), newest response wins;</li>
 * <li>fuzzy — one draft per endpoint group (same normalization the summary
 * cluster uses — ids collapse to :id and turn back into {idN} template vars),
 * representative = newest 2xx frame, falling back to the newest frame so a
 * genuinely-failing endpoint can still be replayed as its failure;</li>
 * <li>frames without an upstream answer (transport failure, status null) are
 * skipped — there is no recorded response to promote;</li>
 * <li>masked header values (the pipeline wrote "***") never ride into a stub
 * as if they were real secrets, and content-length is dropped because the
 * captured body may have been truncated by the size gate.</li>
 * </ul>
 */
class CassetteDraftMapperTest {

    private final CassetteDraftMapper mapper = new CassetteDraftMapper();
    private final ObjectMapper json = new ObjectMapper();

    private MockRecordingFrameMessage frame(String frameId, String method, String path,
                                            Integer status, Map<String, String> responseHeaders,
                                            String responseBody, long occurredAt) {
        return new MockRecordingFrameMessage(frameId, method, path, Map.of(), null,
                status, responseHeaders, responseBody, 12, occurredAt, "sk-t362", 7L);
    }

    @Test
    void strictDraftsMapOneRulePerRecordedUrlNewestWins() {
        List<MockStubDraft> drafts = mapper.strictDrafts(7L, List.of(
                frame("f1", "GET", "/payments/123", 200, Map.of("x-trace", "t1"), "{\"ok\":1}", 1L),
                frame("f2", "GET", "/payments/123", 201, Map.of("x-trace", "t2"), "{\"ok\":2}", 5L),
                frame("f3", "GET", "/payments/456", 200, Map.of(), "[]", 2L)));

        assertEquals(2, drafts.size());
        MockStubDraft p123 = drafts.stream()
                .filter(d -> d.pathPattern().equals("/payments/123")).findFirst().orElseThrow();
        assertEquals("GET", p123.method());
        assertEquals("exact", p123.pathMatchType());
        assertEquals("record", p123.source());
        assertEquals("recording:7", p123.sourceRef());
        assertEquals("[]", p123.matchersJson());
        JsonNode doc = readJson(p123.responseJson());
        assertEquals(201, doc.get("status").asInt(), "newest frame must answer");
        assertEquals("static", doc.get("bodyMode").asText());
        assertEquals("{\"ok\":2}", doc.get("body").asText());
        assertEquals("t2", doc.get("headers").get("x-trace").asText());
    }

    @Test
    void strictDraftsStripQueryAndSkipUpstreamFailures() {
        List<MockStubDraft> drafts = mapper.strictDrafts(7L, List.of(
                frame("f1", "GET", "/orders?page=2", 200, Map.of(), "[]", 1L),
                frame("f2", "GET", "/orders?page=9", 200, Map.of(), "[1]", 2L),
                frame("f3", "GET", "/health", null, Map.of(), null, 3L)));

        assertEquals(1, drafts.size());
        assertEquals("/orders", drafts.get(0).pathPattern());
    }

    @Test
    void fuzzyDraftsCollapseIdFloodIntoTemplatePaths() {
        List<MockStubDraft> drafts = mapper.fuzzyDrafts(7L, List.of(
                frame("f1", "GET", "/payments/123", 200, Map.of(), "{}", 1L),
                frame("f2", "GET", "/payments/456", 200, Map.of(), "{}", 2L),
                frame("f3", "GET", "/users/2f1e3d4c-5b6a-4789-a012-3456789abcde/profile",
                        200, Map.of(), "{}", 3L)));

        assertEquals(2, drafts.size());
        MockStubDraft payments = drafts.stream()
                .filter(d -> d.pathPattern().startsWith("/payments")).findFirst().orElseThrow();
        assertEquals("template", payments.pathMatchType());
        assertEquals("/payments/{id}", payments.pathPattern());
        assertEquals("GET /payments/:id", payments.endpointKey());
        MockStubDraft profile = drafts.stream()
                .filter(d -> d.pathPattern().startsWith("/users")).findFirst().orElseThrow();
        assertEquals("/users/{id}/profile", profile.pathPattern());
    }

    @Test
    void fuzzyDraftsHandleTwoIdSegmentsWithDistinctVars() {
        List<MockStubDraft> drafts = mapper.fuzzyDrafts(7L, List.of(
                frame("f1", "GET", "/a/1/b/2", 200, Map.of(), "{}", 1L)));
        assertEquals("/a/{id1}/b/{id2}", drafts.get(0).pathPattern());
    }

    @Test
    void fuzzyRepresentativePrefersNewestSuccessButKeepsHonestFailure() {
        List<MockStubDraft> mixed = mapper.fuzzyDrafts(7L, List.of(
                frame("f1", "GET", "/payments/1", 200, Map.of(), "{\"a\":1}", 1L),
                frame("f2", "GET", "/payments/2", 502, Map.of(), "bad-gateway", 9L)));
        JsonNode okDoc = readJson(mixed.get(0).responseJson());
        assertEquals(200, okDoc.get("status").asInt(), "newest 2xx answers the fuzzy rule");
        assertEquals("{\"a\":1}", okDoc.get("body").asText());

        List<MockStubDraft> allBad = mapper.fuzzyDrafts(7L, List.of(
                frame("f3", "GET", "/flaky/1", 502, Map.of(), "nope", 1L),
                frame("f4", "GET", "/flaky/2", 503, Map.of(), "later", 2L)));
        JsonNode badDoc = readJson(allBad.get(0).responseJson());
        assertEquals(503, badDoc.get("status").asInt(),
                "a group of pure failures replays its newest honest failure");
    }

    @Test
    void groupsWithoutIdSegmentsStayExactInFuzzyPlan() {
        List<MockStubDraft> drafts = mapper.fuzzyDrafts(7L, List.of(
                frame("f1", "POST", "/auth/login", 200, Map.of(), "{}", 1L)));
        assertEquals("exact", drafts.get(0).pathMatchType());
        assertEquals("/auth/login", drafts.get(0).pathPattern());
        assertEquals("POST /auth/login", drafts.get(0).endpointKey());
    }

    @Test
    void maskedAndSelfContradictingHeadersAreDroppedHonestly() {
        List<MockStubDraft> drafts = mapper.strictDrafts(7L, List.of(
                frame("f1", "GET", "/me", 200,
                        Map.of("authorization", "***", "x-trace", "keep-me", "content-length", "5000"),
                        "{}", 1L)));
        JsonNode doc = readJson(drafts.get(0).responseJson());
        JsonNode headers = doc.get("headers");
        assertTrue(headers.has("authorization") == false,
                "a masked placeholder must never ride into a stub as a real credential");
        assertTrue(headers.has("content-length") == false,
                "captured length can contradict the size-gated body");
        assertEquals("keep-me", headers.get("x-trace").asText());
    }

    @Test
    void draftNamesCarryMethodAndPathForHumans() {
        List<MockStubDraft> drafts = mapper.strictDrafts(7L, List.of(
                frame("f1", "GET", "/payments/123", 200, Map.of(), "{}", 1L)));
        assertEquals("回放 GET /payments/123", drafts.get(0).name());
    }

    private JsonNode readJson(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new AssertionError("response document must be valid json: " + text, e);
        }
    }
}
