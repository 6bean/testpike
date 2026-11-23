package com.sixbean.testpike.aiservice.llm.domain.component;

/**
 * Three-state answer of one capability probe against a live endpoint
 * (P18 discipline: an unreadable probe is UNKNOWN, never a verdict).
 *
 * <ul>
 *   <li>{@link #PROMOTED} — the schema block rode the wire and the endpoint
 *       enforced it (every required key present in the returned content);
 *       the ledger row flips to enforced = 1.</li>
 *   <li>{@link #STILL_UNENFORCED} — the call succeeded but the content
 *       ignores the schema contract (prose or missing required keys); the
 *       row keeps enforced = 0, only its probe timestamp moves.</li>
 *   <li>{@link #UNKNOWN} — nothing was proved: mock endpoint, transport
 *       failure, or a tool-call answer without judging material. No ledger
 *       write, no promotion, no demotion.</li>
 * </ul>
 */
public enum LlmProbeVerdict {
    PROMOTED,
    STILL_UNENFORCED,
    UNKNOWN
}
