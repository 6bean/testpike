package com.sixbean.testpike.core.document.api.dto;

/**
 * <p>preempt= preemption flag: while the previous extraction generation is
 * still live, true=confirm the preemption and admit a new run (the old generation
 * self-aborts at its ai-service generation seams); false/absent=keep the
 * wait-rejection.
 *
 * @param dimensionHint targeted supplementary-extraction dimension (nullable, core-whitelisted)
 * @param preempt preemption confirmation flag (default false = keep the
 * wait-rejection while a previous generation is still running)
 * @param chunkIds segment B directed-supplement whitelist (nullable;
 * non-empty = run exactly one round over these chunks of this
 * document — ids are validated against the document's own rows
 * before anything moves; null/absent = full-document run)
 */
public record ExtractRequest(String modelHint, String dimensionHint, boolean preempt,
                             java.util.List<Long> chunkIds) {

    /** Pre-supplement builder kept for the two-phase migration. */
    public ExtractRequest(String modelHint, String dimensionHint, boolean preempt) {
        this(modelHint, dimensionHint, preempt, null);
    }

    /** Pre-hint builder kept for the two-phase migration (no hint, no preemption). */
    public ExtractRequest(String modelHint, String dimensionHint) {
        this(modelHint, dimensionHint, false, null);
    }

    /** Legacy single-argument builder (two-phase migration: no hint = plain pass). */
    public ExtractRequest(String modelHint) {
        this(modelHint, null, false, null);
    }
}
