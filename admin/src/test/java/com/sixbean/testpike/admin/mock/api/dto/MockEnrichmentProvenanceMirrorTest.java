package com.sixbean.testpike.admin.mock.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enrichment provenance mirror contract (MJ provenance closure, admin bridge):
 * the core rows and drafts now carry enriched_by, and the admin views are a
 * field-for-field mirror passed through untouched by the thin bridge. This
 * pins that a producer payload carrying the anchor deserializes into the
 * mirror with the value intact, an older producer payload without the field
 * still deserializes (reads as absent, never as an error), and the import
 * door round-trips the anchor back out toward core.
 */
class MockEnrichmentProvenanceMirrorTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void stubViewCarriesTheEnrichmentAnchorFromCoreShape() throws Exception {
        String coreJson = """
                {"id":11,"spaceId":2,"name":"rule-a","priority":10,"method":"GET",
                 "pathMatchType":"exact","pathPattern":"/a","matchersJson":"[]","responseJson":"{}",
                 "scenarioName":null,"requiredState":null,"newState":null,
                 "source":"openapi","sourceRef":"spec:9001","enabled":true,"version":0,
                 "createdAt":"2026-11-25T10:00:00","updatedAt":"2026-11-25T10:00:00",
                 "enrichedBy":"job:4242"}
                """;

        MockStubView view = mapper.readValue(coreJson, MockStubView.class);

        assertEquals("job:4242", view.enrichedBy());
    }

    @Test
    void stubViewToleratesProducerWithoutTheAnchor() throws Exception {
        String coreJson = """
                {"id":12,"spaceId":2,"name":"rule-b","priority":20,"method":"GET",
                 "pathMatchType":"exact","pathPattern":"/b","matchersJson":"[]","responseJson":"{}",
                 "scenarioName":null,"requiredState":null,"newState":null,
                 "source":"manual","sourceRef":null,"enabled":true,"version":0,
                 "createdAt":"2026-11-25T10:00:00","updatedAt":"2026-11-25T10:00:00"}
                """;

        MockStubView view = mapper.readValue(coreJson, MockStubView.class);

        assertNull(view.enrichedBy());
    }

    @Test
    void draftViewRoundTripsTheAnchorBackTowardCore() throws Exception {
        MockStubDraftView draft = new MockStubDraftView("GET /c", "rule-c", "GET", "exact", "/c",
                "[]", "{}", "openapi", "spec:9001", "job:777");

        String wire = mapper.writeValueAsString(draft);
        MockStubDraftView back = mapper.readValue(wire, MockStubDraftView.class);

        assertEquals("job:777", back.enrichedBy(),
                "enriched drafts keep the anchor through the import door");
        List<MockStubDraftView> batch = List.of(draft);
        assertTrue(mapper.writeValueAsString(batch).contains("\"enrichedBy\":\"job:777\""),
                "the wizard posts enriched drafts with the anchor intact");
    }
}
