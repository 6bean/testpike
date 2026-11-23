package com.sixbean.testpike.admin.ai.api.dto;

import java.util.List;

/**
 *
 * @param reviewFlagThreshold Low-confidence review-flag threshold echo (single source =
 * core config key; clients must not hard-code a second value).
 * @param uncoveredChunkCount Pending coverage-ledger steps of this generation
 * (honest gap disclosure; 0 when nothing is tracked).
 * @param uncoveredChunkIds Chunk row ids behind the pending steps (ascending).
 * @param dimensionSuggestions Missing review dimensions of the whole preview,
 * echoed from core so the clickable chips render on the
 * user-facing channel (the mirror must not drop fields).
 * @param fidelity Fidelity ledger echo of the current generation (:
 * how much of the source the run actually read). Null
 * before the run lands its ledger row — the mirror
 * must not invent one.
 * @param uncoveredChunks Per-chunk reason rows of the pending coverage steps
 * ( segment A: the ledger's own note/evidence plus
 * chunk metadata). The mirror must not drop them — the
 * residual-gap banner answers "why" from these rows.
 */
public record ExtractionPreviewView(long documentId, int status, String extractError,
                                    int page, int size, long total,
                                    List<ExtractionItemView> items,
                                    Double reviewFlagThreshold,
                                    int uncoveredChunkCount,
                                    List<Long> uncoveredChunkIds,
                                    List<String> dimensionSuggestions,
                                    List<ExtractionStageView> stageLedger,
                                    String lastActivityAt,
                                    ExtractionFidelityView fidelity,
                                    List<ExtractionGapView> uncoveredChunks) {
}
