package com.sixbean.testpike.admin.ai.api.dto;

/**
 * One preceding source chunk beside the requested chunk (mirror of core
 * ChunkNeighbor, WR batch segment C; fields one-to-one).
 *
 * @param id tp_document_chunk row id of the neighbour (evidence anchor)
 * @param chapter chapter title the neighbour belongs to
 * @param seq chunk sequence inside the document
 * @param content neighbour original text (already trimmed to the context budget)
 */
public record ChunkNeighborView(long id, String chapter, int seq, String content) {
}
