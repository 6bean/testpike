package com.sixbean.testpike.core.config.domain.component;

import java.util.Map;

/**
 * Write-side value ranges + shared key/default names for the ai.pipeline.*
 * extraction-pipeline key group .
 *
 * <p>Mirrors the AiCtxKeyRanges / AiOcrKeyRanges precedent: the K8 convention
 * puts no CHECK constraint in the DB, this table is the single source of
 * intervals for the write guard in ConfigServiceImpl, and the readers clamp
 * against the same intervals on their side — GroupParallelFanout.HARD_CAP (3)
 * for the fan-out row and DocExtractServiceImpl.GROUP_RETRY_HARD_CAP (2) with
 * factory defaults 1/1 for the two long-tail bounds. Key names and factory
 * defaults live here so the write guard, the seeded migration rows and the
 * consuming services can never drift apart.
 *
 * <p>The upper bounds are adjudicated caps (they come from the
 * worst-case retry ledger against the untouched 7200s task deadline): the
 * operator may lower a value, never raise it past the cap, so the write guard
 * rejects out-of-range instead of silently clamping — a config edit must not
 * be able to turn into an unadjudicated capacity change.
 */
public final class AiPipelineKeyRanges {

    /** Per-group LLM calls started concurrently across the three grouped call-sites. */
    public static final String KEY_GROUP_PARALLEL = "ai.pipeline.group-parallel";
    /** Inline retries one failing group burns inside the hot loop before removal. */
    public static final String KEY_GROUP_INLINE_RETRY_LIMIT = "ai.pipeline.group-inline-retry-limit";
    /** Bounded deferred re-submission rounds for the removed long-tail groups. */
    public static final String KEY_GROUP_DEFERRED_ROUNDS = "ai.pipeline.group-deferred-rounds";

    /** Fallback defaults (byte-aligned with the V529/V530 seeds — single source, no second default face). */
    public static final int DEFAULT_GROUP_PARALLEL = 3;
    public static final int DEFAULT_GROUP_INLINE_RETRY_LIMIT = 1;
    public static final int DEFAULT_GROUP_DEFERRED_ROUNDS = 1;

    /** Int keys the group interval-guards: key → {lower, upper} (mirror of the reader clamps). */
    private static final Map<String, int[]> INT_RANGES = Map.of(
            KEY_GROUP_PARALLEL, new int[]{1, 3},
            KEY_GROUP_INLINE_RETRY_LIMIT, new int[]{0, 2},
            KEY_GROUP_DEFERRED_ROUNDS, new int[]{0, 2});

    /** Prefix of the whole group (write-side entry check). */
    public static final String KEY_PREFIX = "ai.pipeline.";

    private AiPipelineKeyRanges() {
    }

    /** Interval for an ai.pipeline.* int key, or null when not interval-guarded. */
    public static int[] rangeOf(String key) {
        return INT_RANGES.get(key);
    }

    /** True for any ai.pipeline.* group key (single write-side dispatch check). */
    public static boolean isPipelineKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }
}
