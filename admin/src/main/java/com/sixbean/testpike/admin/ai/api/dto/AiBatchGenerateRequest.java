package com.sixbean.testpike.admin.ai.api.dto;

import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.common.aibatch.BatchItemNormalizer;

import java.util.List;

/**
 * AI batch generation request (user-facing form of the batch contract, document section 2.7).
 * <p>Dual form : structured {@code items} entries carry the optional requirement
 * link; the legacy {@code requirements} plain-text list keeps working for one version cycle.
 * The single count/length ceiling stays in the core service (K8 key ai.batch.max-count).
 *
 * @param projectId project ID (used end-to-end)
 * @param requirements legacy plain-text requirement list (kept one compatibility cycle)
 * @param items structured entries with optional requirement-point linkage (wins when present)
 * @param previewBatchId stage0 scenario preview batch whose counts multiply the
 * estimate ; null keeps the legacy per-line shape
 */
public record AiBatchGenerateRequest(long projectId, List<String> requirements, List<AiBatchItem> items,
                                     Long previewBatchId) {

    /** Legacy two-argument call sites keep compiling (plain-text form only). */
    public AiBatchGenerateRequest(long projectId, List<String> requirements) {
        this(projectId, requirements, null, null);
    }

    /** Legacy three-argument call sites keep compiling (no preview multiplier). */
    public AiBatchGenerateRequest(long projectId, List<String> requirements, List<AiBatchItem> items) {
        this(projectId, requirements, items, null);
    }

    /** Canonical item list forwarded to core (delegates to the shared normalizer, no second copy). */
    public List<AiBatchItem> normalizedItems() {
        return BatchItemNormalizer.normalize(items, requirements);
    }
}
