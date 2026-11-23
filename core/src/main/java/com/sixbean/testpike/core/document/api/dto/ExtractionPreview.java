package com.sixbean.testpike.core.document.api.dto;

import java.util.List;

/**
 *
 * @param reviewFlagThreshold Low-confidence review-flag threshold :
 * single source = hot-updatable config key
 * ai.doc.review-flag-threshold, echoed with the preview so no
 * client may hard-code a second value source.
 * @param uncoveredChunkCount Pending steps of this generation's coverage ledger
 * (item 3: honest gap disclosure; an absent ledger answers 0,
 * never a fabricated gap).
 * @param uncoveredChunkIds Source chunk row ids behind those pending steps (ascending;
 * the jump-back reuses the AP-4 chunk viewer, no second anchor).
 * @param dimensionSuggestions Missing review dimensions of the whole preview:
 * exception-flow / boundary / non-functional minus what any item
 * already presents; server-computed, pagination-independent; empty
 * when the ai.doc.dimension-suggest switch is off or nothing is
 * missing — a chip per entry, never a hard gate.
 * @param fidelity Read-only echo of the extraction fidelity ledger for THIS
 * generation injected/source tokens,
 * retention %, anchor fill, measured per-item output
 * coefficients). Null before the row lands or when the
 * door is unreachable — the preview never fabricates a
 * retention number.
 * @param uncoveredChunks Per-chunk reason rows of the pending ledger steps
 * ( segment A: note/evidence from the coverage
 * ledger plus seq/chapter/contentChars from
 * tp_document_chunk). Empty when nothing is pending or
 * the ledger is absent — the same fail-soft posture as
 * the uncovered count.
 */
public record ExtractionPreview(long documentId, int status, String extractError,
                                int page, int size, long total,
                                List<ExtractionItem> items,
                                Double reviewFlagThreshold,
                                int uncoveredChunkCount,
                                List<Long> uncoveredChunkIds,
                                List<String> dimensionSuggestions,
                                List<ExtractionStageRow> stageLedger,
                                String lastActivityAt,
                                ExtractionFidelityRow fidelity,
                                List<ExtractionGapRow> uncoveredChunks) {
}
