package com.sixbean.testpike.core.document.api.dto;

/**
 * One preceding source chunk shown next to the requested chunk in the
 * extraction preview's jump-to-source viewer .
 *
 * <p>A thin chunk (the live incident: 13 characters) reads as nothing on its
 * own; the viewer needs the neighbouring original text to make sense of it.
 * Neighbours are the closest chunks of the SAME document in ascending seq.
 *
 * @param id tp_document_chunk.id (evidence anchor of this neighbour)
 * @param chapter chapter title the neighbour belongs to
 * @param seq chunk sequence inside the document (1-based)
 * @param content neighbour original text (counted against the hot-updatable
 * context budget; farthest neighbours are dropped first)
 */
public record ChunkNeighbor(long id, String chapter, int seq, String content) {
}
