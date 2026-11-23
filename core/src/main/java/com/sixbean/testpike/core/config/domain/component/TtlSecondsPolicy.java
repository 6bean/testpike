package com.sixbean.testpike.core.config.domain.component;

/**
 * Single-source resolution rule for hot-updatable TTL seconds.
 * <p>Every one-shot gate in the platform (write-tool tickets, draft tickets, MCP
 * in-session confirmation references, callback nonces) keeps its validity window in
 * {@code tp_sys_config} and reads it live. The fallback semantics must be identical
 * across all of them — a blank, illegal or non-positive value falls back to that
 * gate's shipped default, never to zero, never to a cached stale value. Third copy
 * of this rule triggered the extraction (R40: one implementation per logic).
 */
public final class TtlSecondsPolicy {

    private TtlSecondsPolicy() {
    }

    /** Parse the raw configured value; illegal shapes fall back to the default. */
    public static long resolve(String raw, long defaultSeconds) {
        if (raw == null || raw.isBlank()) {
            return defaultSeconds;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : defaultSeconds;
        } catch (NumberFormatException e) {
            return defaultSeconds;
        }
    }
}
