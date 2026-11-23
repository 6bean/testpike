package com.sixbean.testpike.core.requirement.api.dto;

import java.util.List;

/**
 * Evidence anchor captured at adoption time :
 * the verbatim excerpt and its source chunk ids stored on the requirement
 * row so the generation chain can later pull the original text block
 * without re-parsing document previews. A fully-empty anchor means
 * "unanchored" - manual points keep byte-for-byte the old behaviour.
 *
 * @param documentId source document id (tp_document.id)
 * @param chunkIds source chunk row ids (tp_document_chunk.id; empty when
 * the excerpt could not be attributed to a chunk)
 * @param evidence verbatim supporting excerpt given by the model at
 * extraction time (null when the model gave none)
 */
public record RequirementSourceAnchor(Long documentId, List<Long> chunkIds, String evidence) {

    /** Returns null when there is no anchor information at all - the single-source
     *  emptiness rule so no call site writes its own variant of the check. */
    public static RequirementSourceAnchor ofNullable(Long documentId, Long chunkId, String evidence) {
        List<Long> chunkIds = chunkId == null ? List.of() : List.of(chunkId);
        if (documentId == null && chunkIds.isEmpty() && (evidence == null || evidence.isBlank())) {
            return null;
        }
        return new RequirementSourceAnchor(documentId, chunkIds, evidence);
    }
}
