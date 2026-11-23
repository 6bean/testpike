package com.sixbean.testpike.core.config.domain.component;

import java.util.Map;
import java.util.Set;

/**
 * Write-side value ranges + shared key/default names for the ai.mcp.sync.*
 * knowledge-sync config key group .
 *
 * <p>Mirrors the AiChatKeyRanges precedent: the K8 convention puts no CHECK
 * constraint in the DB, this table is the single source of intervals for the
 * write guard in ConfigServiceImpl, and readers fall back to the DEFAULT_*
 * constants below on missing/garbage values (fail-closed). Key names and
 * defaults live here so the write guard, the seeded migration row and the
 * consuming sync service can never drift apart.
 */
public final class AiMcpSyncKeyRanges {

    /** Master switch in front of every scheduled knowledge-sync scan. */
    public static final String KEY_ENABLED = "ai.mcp.sync.enabled";
    /** Default cadence applied to a subscription saved without an explicit interval. */
    public static final String KEY_INTERVAL_MINUTES = "ai.mcp.sync.interval-minutes";
    /** Upper bound of subscriptions one scan round may execute. */
    public static final String KEY_MAX_ENTRIES_PER_ROUND = "ai.mcp.sync.max-entries-per-round";
    /** Consecutive failed rounds after which a subscription auto-pauses with an alert. */
    public static final String KEY_FAILURE_THRESHOLD = "ai.mcp.sync.failure-threshold";

    /** Fallback defaults (byte-aligned with the V344 seed — single source, no second default face). */
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_INTERVAL_MINUTES = 1440;
    public static final int DEFAULT_MAX_ENTRIES_PER_ROUND = 200;
    public static final int DEFAULT_FAILURE_THRESHOLD = 3;

    /** Int keys the group interval-guards: key → {lower, upper}. */
    private static final Map<String, int[]> INT_RANGES = Map.of(
            KEY_INTERVAL_MINUTES, new int[]{1, 43200},
            KEY_MAX_ENTRIES_PER_ROUND, new int[]{1, 5000},
            KEY_FAILURE_THRESHOLD, new int[]{1, 20});

    /** Bool keys the group accepts only true/false for. */
    private static final Set<String> BOOL_KEYS = Set.of(KEY_ENABLED);

    /** Prefix of the whole group (write-side entry check). */
    public static final String KEY_PREFIX = "ai.mcp.sync.";

    private AiMcpSyncKeyRanges() {
    }

    /** Interval for an ai.mcp.sync.* int key, or null when not interval-guarded. */
    public static int[] rangeOf(String key) {
        return INT_RANGES.get(key);
    }

    /** True for ai.mcp.sync.* bool switches (true/false only). */
    public static boolean isBoolKey(String key) {
        return key != null && BOOL_KEYS.contains(key);
    }

    /** True for any ai.mcp.sync.* group key (single write-side dispatch check). */
    public static boolean isMcpSyncKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }
}
