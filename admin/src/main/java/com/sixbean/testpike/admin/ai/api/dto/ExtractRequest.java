package com.sixbean.testpike.admin.ai.api.dto;

import java.util.List;

/**
 *
 * @param dimensionHint targeted supplementary-extraction dimension (nullable)
 * @param preempt preemption confirmation flag (default false)
 * @param chunkIds directed-supplement whitelist (empty = full run,
 * same semantics as the core side; ids must belong to this
 * document or core rejects honestly before dispatch)
 */
public record ExtractRequest(String modelHint, String dimensionHint, boolean preempt,
                             List<Long> chunkIds) {

    /** Pre-whitelist builder kept for the two-phase migration (full run). */
    public ExtractRequest(String modelHint, String dimensionHint, boolean preempt) {
        this(modelHint, dimensionHint, preempt, List.of());
    }

    /** Pre-hint builder kept for the two-phase migration (no hint, no preemption). */
    public ExtractRequest(String modelHint, String dimensionHint) {
        this(modelHint, dimensionHint, false, List.of());
    }

    /** Legacy single-argument builder (two-phase migration: no hint = plain pass). */
    public ExtractRequest(String modelHint) {
        this(modelHint, null, false, List.of());
    }
}
