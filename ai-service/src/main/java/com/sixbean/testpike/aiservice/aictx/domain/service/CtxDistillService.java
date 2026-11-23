package com.sixbean.testpike.aiservice.aictx.domain.service;

/**
 * Incremental correction-distillation service .
 *
 * <p>Reads the four project-feedback sources (review rejections, adopted
 * healing diffs, dialog downvotes, tool refusals) through per-source
 * last-id watermarks so nothing is ever rescanned, distils each project's
 * new rows into reusable convention entries through the LLM fallback chain,
 * and persists them into tp_ai_ctx_memory for the assembly injection face.
 */
public interface CtxDistillService {

    /**
     * One incremental pass over all four sources.
     *
     * @return number of project-memory entries created in this pass
     */
    int distillOnce();
}
