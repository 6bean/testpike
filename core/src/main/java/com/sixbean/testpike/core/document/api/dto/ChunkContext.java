package com.sixbean.testpike.core.document.api.dto;

import java.util.List;

/**
 * Context door of a single source chunk for the extraction preview's
 * jump-to-source viewer .
 *
 * <p>Besides the chunk itself the viewer gets up to three preceding neighbours
 * of the same document plus the chunk's position marker, so a 13-character
 * sliver can be read inside its surrounding original text. The neighbour text
 * is capped by the hot-updatable key {@code ai.doc.chunk-context-chars};
 * over budget the FARTHEST neighbours are dropped first (nearest kept).
 *
 * @param chunkId tp_document_chunk.id of the requested chunk
 * @param seq its sequence inside the document (1-based)
 * @param chapter chapter title of the requested chunk
 * @param content the chunk's own original text (never trimmed — it is
 * the anchor the user clicked)
 * @param contentChars length of {@link #content} (why the sliver reads small)
 * @param positionIndex 1-based ordinal of this chunk among the document's
 * @param preceding closest preceding neighbours in ascending seq order,
 * already trimmed to the context budget (may be empty)
 */
public record ChunkContext(long chunkId, int seq, String chapter, String content,
                           int contentChars, int positionIndex, List<ChunkNeighbor> preceding,
                           boolean found) {
}
