package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage-1 generation landing anchor .
 * <p>Why this exists: a generated case inherits the evidence anchor of the
 * requirement point it was produced from (document + chunk ids already stored
 * on tp_requirement.source_anchor). Without this stamp every generated step
 * landed with anchor NULL and the review/repair UIs could not trace a step
 * back to the concrete original-text blocks (live window 2026-11-21:
 * steps=6 anchored=0). The payload shape is owned by the core single-source
 * normalizer (CaseStepAnchorNormalizer, kind=source registered there); this
 * class only assembles the legal source-kind object and never copies the
 * element-dialect derivation logic (R40).
 * <p>Legacy guarantee: when the requirement carries no document anchor the
 * stamp returns null and the landing payload keeps its pre- shape
 * byte-for-byte (zero drift, no anchor key in the step map).
 */
public final class GenStepEvidenceAnchor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GenStepEvidenceAnchor() {
        // Static assembly helper: no state, no injection, single place.
    }

    /**
     * Build the per-step evidence anchor for a generated case.
     *
     * @param requirementId the requirement point the generation task consumed
     * @param documentId    document id from the requirement source anchor
     * @param chunkIds      hit chunk ids (null/empty = document-only evidence)
     * @return canonical jsonb object text (kind=source), or null when there is
     *         no requirement/document anchor to inherit
     */
    public static String sourceAnchorOf(Long requirementId, Long documentId, List<Long> chunkIds) {
        if (requirementId == null || documentId == null) {
            return null;
        }
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("kind", "source");
        anchor.put("requirementId", requirementId);
        anchor.put("documentId", documentId);
        if (chunkIds != null && !chunkIds.isEmpty()) {
            anchor.put("chunkIds", List.copyOf(chunkIds));
        }
        try {
            return MAPPER.writeValueAsString(anchor);
        } catch (Exception e) {
            // Serializing string/number values cannot realistically fail; stay
            // conservative and keep the legacy anchor-free payload.
            return null;
        }
    }
}
