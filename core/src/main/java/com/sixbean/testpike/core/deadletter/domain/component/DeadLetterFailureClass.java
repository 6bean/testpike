package com.sixbean.testpike.core.deadletter.domain.component;

/**
 * <p>Every AJ consumer keys off this enum — the ledger row's failure_class, the
 * ops page grouping/gray-out, the ENTITY_GONE replay ban (O3-a-2) and the
 * auto-replay whitelist (PROVIDER_TRANSIENT/CONFIG_MISSING/LLM_TIMEOUT) — so it
 * is defined once here and never re-encoded as loose strings elsewhere.
 */
public enum DeadLetterFailureClass {

    /** The business entity the message pointed at no longer exists (≈70% of the dev backlog). Replay forbidden. */
    ENTITY_GONE,

    /** Upstream provider/peer was unreachable or dropped the socket; safe to retry once health returns. */
    PROVIDER_TRANSIENT,

    /** Required configuration is absent (embedding catalog, routing). Auto-replay waits for the config fix. */
    CONFIG_MISSING,

    /** The model call exceeded its time budget; replay allowed after provider recovery. */
    LLM_TIMEOUT,

    /** Payload could not be deserialized (corrupted or schema-broken body). */
    DESERIALIZATION_FAIL,

    
    UNKNOWN
}
