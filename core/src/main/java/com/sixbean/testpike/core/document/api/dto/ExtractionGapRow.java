package com.sixbean.testpike.core.document.api.dto;

/**
 * One uncovered source chunk with the coverage ledger's own reason
 * . The ledger step carries note/evidence written by
 * ai-service; the preview previously dropped them, so the residual-gap
 * banner could not answer "why did this chunk produce nothing".
 * seq/chapter/contentChars come from tp_document_chunk by id (single
 * source, batched); a chunk row deleted after the run keeps the note and
 * answers null/0 honestly — never a fabricated chapter name.
 *
 * @param chunkId tp_document_chunk row id behind the pending step
 * @param seq in-document order (null when the chunk row is gone)
 * @param chapter heuristic chapter attribution (nullable at source)
 * @param note residual-account sentence from the ledger ("" absent)
 * @param evidence ledger evidence ("" for pending steps, kept verbatim)
 * @param contentChars source size of the chunk row (0 when the row is gone)
 */
public record ExtractionGapRow(long chunkId, Integer seq, String chapter,
                               String note, String evidence, int contentChars) {
}
