package com.sixbean.testpike.core.ai.api.dto;

import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.common.aibatch.BatchItemNormalizer;

import java.util.List;

/**
 * AI batch generation request body (batch generation is moved earlier; batch contract is in document section 2.7).
 * <p>Dual form : the structured {@code items} list carries per-entry requirement
 * linkage; the legacy {@code requirements} plain-text list keeps working for one version cycle.
 * The merge rule lives in the shared BatchItemNormalizer (single source, no second copy).
 *
 * @param projectId project ID (used end-to-end; returns 404 if not found).
 * @param requirements legacy plain-text requirement list (each entry non-empty, ≤5000 characters; kept one cycle).
 * @param items structured entries with optional requirement-point linkage (wins when present and non-empty).
 * @param previewBatchId stage0 scenario preview batch whose per-line scenario counts
 * multiply the cost estimate ; null keeps the legacy shape.
 * @param windowFeedDeclared verification-window feed declaration (transported from the
 * admin X-Window-Feed header); the server still checks the hot switch and the
 * project whitelist before routing the batch to the priority queue.
 */
public record AiBatchCreateRequest(long projectId, List<String> requirements, List<AiBatchItem> items,
                                   Long previewBatchId, boolean windowFeedDeclared) {

    /** Legacy two-argument call sites keep compiling (plain-text form only). */
    public AiBatchCreateRequest(long projectId, List<String> requirements) {
        this(projectId, requirements, null, null, false);
    }

    /** Three-argument call sites keep compiling (no preview multiplier). */
    public AiBatchCreateRequest(long projectId, List<String> requirements, List<AiBatchItem> items) {
        this(projectId, requirements, items, null, false);
    }

    /** Four-argument call sites keep compiling (no window declaration). */
    public AiBatchCreateRequest(long projectId, List<String> requirements, List<AiBatchItem> items,
                                Long previewBatchId) {
        this(projectId, requirements, items, previewBatchId, false);
    }

    /** Canonical item list for the whole chain (delegates to the shared normalizer). */
    public List<AiBatchItem> normalizedItems() {
        return BatchItemNormalizer.normalize(items, requirements);
    }
}
