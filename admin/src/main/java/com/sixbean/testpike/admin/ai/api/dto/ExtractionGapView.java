package com.sixbean.testpike.admin.ai.api.dto;

/**
 * Admin mirror of core ExtractionGapRow : one uncovered
 * source chunk with the coverage ledger's own reason. The extraction
 * preview passthrough must not drop fields — the residual-gap banner
 * reads its "why" straight from these rows.
 *
 * @param chunkId tp_document_chunk row id behind the pending step
 * @param seq in-document order (null when the chunk row is gone)
 * @param chapter heuristic chapter attribution (nullable at source)
 * @param note residual-account sentence from the ledger ("" absent)
 * @param evidence ledger evidence ("" for pending steps, kept verbatim)
 * @param contentChars source size of the chunk row (0 when the row is gone)
 */
public record ExtractionGapView(long chunkId, Integer seq, String chapter,
                                String note, String evidence, int contentChars) {
}
