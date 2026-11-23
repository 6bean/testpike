package com.sixbean.testpike.admin.ai.api.dto;

import java.util.List;

/**
 * Context door of one source chunk (mirror of core ChunkContext, WR batch
 * segment C): the requested chunk verbatim plus its closest preceding neighbours
 * and the position marker, so the extraction preview can explain a thin chunk
 * with its surrounding original text instead of showing a 13-character sliver.
 * Fields pass through one-to-one, nothing renamed or dropped.
 *
 * @param chunkId tp_document_chunk.id of the requested chunk
 * @param seq its sequence inside the document (core row value)
 * @param chapter chapter title of the requested chunk
 * @param content the chunk's own original text (never trimmed)
 * @param contentChars length of content (why a sliver reads small)
 * @param positionIndex 1-based ordinal among the document's chunks
 * @param preceding closest preceding neighbours, ascending seq (may be empty)
 */
public record ChunkContextView(long chunkId, int seq, String chapter, String content,
                               int contentChars, int positionIndex,
                               List<ChunkNeighborView> preceding, boolean found) {
}
