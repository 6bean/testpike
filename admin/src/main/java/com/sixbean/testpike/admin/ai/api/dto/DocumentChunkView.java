package com.sixbean.testpike.admin.ai.api.dto;

/**
 * One source chunk of a requirement document (AP-4 evidence-anchor read surface).
 * The extraction preview uses it to jump from an item's evidence excerpt back to
 * the verbatim chunk text; fields mirror the core {@code /internal/documents/{id}/chunks}
 * row shape one-to-one (id/chapter/seq/content), nothing is renamed or dropped.
 *
 * @param id      tp_document_chunk row id (the anchor an extraction item carries)
 * @param chapter chapter label of the chunk (nullable for flat documents)
 * @param seq     zero-based order of the chunk inside its document
 * @param content verbatim chunk text
 */
public record DocumentChunkView(long id, String chapter, int seq, String content) {
}
