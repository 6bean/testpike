package com.sixbean.testpike.core.tool.domain.component;

/**
 * GA5 : the four operational error classes of external /mcp calls,
 * plus the honest UNCLASSIFIED tail. The vocabulary exists so the
 * self-evolution loop can tell "the gate said no" (refused) from "the door
 * was not ours" (forbidden), "the peer never answered" (timeout) and
 * "the same key was replayed" (idempotent conflict) — each implies a
 * different next action, so they must not share one bucket.
 * <p>Declaration order is the reporting order of the ledger rollup.
 */
public enum ExternalCallErrorClass {

    /** A policy or capacity gate refused the call: switch off, quota spent,
     *  rate limit hit, confirm handle already consumed or expired. */
    REFUSED,

    /** The downstream leg produced no answer inside its budget. */
    TIMEOUT,

    /** The same Idempotency-Key was submitted while the first one is running. */
    IDEMPOTENT_CONFLICT,

    /** The presenting identity may not reach this target: visibility, project
     *  scope or token permission denied it. */
    FORBIDDEN,

    /** Recorded failure text outside the harvested vocabulary — the
     *  classifier never guesses, the gap stays visible. */
    UNCLASSIFIED
}
