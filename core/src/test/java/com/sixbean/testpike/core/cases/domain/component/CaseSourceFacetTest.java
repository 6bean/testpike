package com.sixbean.testpike.core.cases.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bidirectional contract for the case provenance facet mapping (review-queue segment).
 *
 * <p>The facet answers one user question: where did this case come from? The four
 * values are in-product conversation, external agent channel, bulk import and manual.
 * The mapping reads only the persisted provenance columns (source + source_ref), so a
 * row created before the marker existed still reports a stable facet instead of null.
 */
class CaseSourceFacetTest {

    @Test
    void externalAgentReferencesMapToOffAgent() {
        assertEquals(CaseSourceFacet.OFF_AGENT, CaseSourceFacet.of(1, "mcp"));
        assertEquals(CaseSourceFacet.OFF_AGENT, CaseSourceFacet.of(1, "mcp:gen"));
        assertEquals(CaseSourceFacet.OFF_AGENT, CaseSourceFacet.of(2, "MCP-Channel"));
    }

    @Test
    void conversationDraftReferencesMapToChat() {
        assertEquals(CaseSourceFacet.CHAT, CaseSourceFacet.of(1, "chat"));
        assertEquals(CaseSourceFacet.CHAT, CaseSourceFacet.of(6, "chat:draft-42"));
    }

    @Test
    void templateAndLegacySourcesMapToImport() {
        assertEquals(CaseSourceFacet.IMPORT, CaseSourceFacet.of(2, null));
        assertEquals(CaseSourceFacet.IMPORT, CaseSourceFacet.of(3, "legacy-pack"));
    }

    @Test
    void everythingElseMapsToManual() {
        assertEquals(CaseSourceFacet.MANUAL, CaseSourceFacet.of(1, null));
        assertEquals(CaseSourceFacet.MANUAL, CaseSourceFacet.of(1, ""));
        assertEquals(CaseSourceFacet.MANUAL, CaseSourceFacet.of(4, "case:88"));
        assertEquals(CaseSourceFacet.MANUAL, CaseSourceFacet.of(5, null));
        assertEquals(CaseSourceFacet.MANUAL, CaseSourceFacet.of(6, null));
    }

    @Test
    void similarWordsAreNotMistakenForTheMarkers() {
        // "channel:9" is not the conversation marker; only the exact "chat"/"mcp"
        // prefixes win, so future free-form references fall back to manual.
        assertEquals(CaseSourceFacet.MANUAL, CaseSourceFacet.of(1, "channel:9"));
        assertEquals(CaseSourceFacet.MANUAL, CaseSourceFacet.of(1, "mcpx-legacy"));
    }
}
