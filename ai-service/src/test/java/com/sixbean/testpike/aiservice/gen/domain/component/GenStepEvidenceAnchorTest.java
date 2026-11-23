package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the stage-1 generation landing anchor .
 * <p>Window incident 2026-11-21: generated cases landed with steps=6 anchored=0
 * even though the requirement itself carries a source anchor (document/chunks).
 * The generation landing path must stamp every generated step with the
 * requirement's evidence anchor (kind=source) so the review/repair UIs can
 * trace a step back to the concrete original-text blocks. Requirements without
 * evidence keep the legacy payload shape (no anchor key, zero drift).
 */
class GenStepEvidenceAnchorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anchoredRequirementProducesSourceEvidenceJson() throws Exception {
        String json = GenStepEvidenceAnchor.sourceAnchorOf(23367L, 9085L, List.of(148477L, 148478L));
        JsonNode node = mapper.readTree(json);
        assertEquals("source", node.path("kind").asText(),
                "kind must be the registered source dialect accepted by the core normalizer");
        assertEquals(23367L, node.path("requirementId").asLong(), "requirement id must survive");
        assertEquals(9085L, node.path("documentId").asLong(), "document id must survive");
        assertEquals(2, node.path("chunkIds").size(), "chunk ids must be a list, not a scalar");
        assertEquals(148477L, node.path("chunkIds").get(0).asLong());
    }

    @Test
    void emptyChunkListKeepsDocumentEvidenceOnly() throws Exception {
        String json = GenStepEvidenceAnchor.sourceAnchorOf(1L, 2L, List.of());
        JsonNode node = mapper.readTree(json);
        assertEquals("source", node.path("kind").asText());
        assertTrue(node.path("chunkIds").isMissingNode() || node.path("chunkIds").isEmpty(),
                "no chunks must not fabricate an empty-looking anchor payload");
    }

    @Test
    void missingEvidenceYieldsNullForLegacyShape() throws Exception {
        assertNull(GenStepEvidenceAnchor.sourceAnchorOf(null, 9085L, List.of(1L)),
                "no requirement id = no anchor stamp");
        assertNull(GenStepEvidenceAnchor.sourceAnchorOf(23367L, null, List.of(1L)),
                "no document id = no anchor stamp");
        // A null chunk list is not a reason to drop the document evidence —
        // the stamp simply omits the chunkIds field (same shape as empty list).
        JsonNode node = mapper.readTree(GenStepEvidenceAnchor.sourceAnchorOf(23367L, 9085L, null));
        assertEquals(9085L, node.path("documentId").asLong());
    }
}
