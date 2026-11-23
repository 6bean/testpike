package com.sixbean.testpike.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size list partitioning (single source for every batch-splitting flow in
 * the platform — red line 6: consumers never re-implement the split loop; the
 * embedding outbound cap clamp and the knowledge ingest writer both consume it).
 * <p>Used by the embedding outbound cap clamping (EmbeddingFallbackChain) and
 * the knowledge ingest batch writer (addInBatches). Chunks are views over the
 * source list: no copy, order preserved, last chunk may be smaller.
 */
public final class ListPartition {

    private ListPartition() {
    }

    /**
     * Split {@code items} into consecutive chunks of at most {@code size} elements.
     *
     * @param items source list (never mutated)
     * @param size  max chunk size, must be {@code >= 1}
     * @return consecutive chunks covering every element exactly once, input order kept
     */
    public static <T> List<List<T>> of(List<T> items, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("chunk size must be >= 1, got " + size);
        }
        List<List<T>> chunks = new ArrayList<>((items.size() + size - 1) / size);
        for (int from = 0; from < items.size(); from += size) {
            chunks.add(items.subList(from, Math.min(from + size, items.size())));
        }
        return chunks;
    }
}
