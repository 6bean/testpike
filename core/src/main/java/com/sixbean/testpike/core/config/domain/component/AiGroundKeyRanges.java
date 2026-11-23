package com.sixbean.testpike.core.config.domain.component;

import java.util.Map;

/**
 * Write-side value ranges for the ai.ground.* grounding material-role config
 * key group (AX behavior-kernel batch, seeded by V400).
 *
 * <p>Mirrors the AiChatKeyRanges / AiCtxKeyRanges precedent: the K8 convention
 * puts no CHECK constraint in the DB, this table is the single source of
 * intervals for the write guard in ConfigServiceImpl, and the reader in
 * ai-service (GroundingService) falls back to its shipped defaults on absent
 * or unparsable values (fail-closed). The two keys split retrieved material
 * into strong support vs weak background (score bar) and mark untouched
 * documents as possibly out-of-date after a stale window (day count).
 */
public final class AiGroundKeyRanges {

    /** Relevance bar between strong and weak material (ratio, 0~1). */
    public static final String KEY_STRONG_SCORE = "ai.ground.strong-score";
    /** Days after which an untouched material is suspected stale (1~365). */
    public static final String KEY_STALE_WINDOW_DAYS = "ai.ground.stale-window-days";

    /** Int keys the group interval-guards: key → {lower, upper}. */
    private static final Map<String, int[]> INT_RANGES = Map.of(
            KEY_STALE_WINDOW_DAYS, new int[]{1, 365});

    /** Double keys the group interval-guards: key → {lower, upper}. */
    private static final Map<String, double[]> DOUBLE_RANGES = Map.of(
            KEY_STRONG_SCORE, new double[]{0.0, 1.0});

    /** Prefix of the whole group (write-side entry check). */
    public static final String KEY_PREFIX = "ai.ground.";

    private AiGroundKeyRanges() {
    }

    /** Interval for the group's int keys, or null when not interval-guarded. */
    public static int[] rangeOf(String key) {
        return INT_RANGES.get(key);
    }

    /** Interval for the group's double keys (ratios), or null when not guarded. */
    public static double[] doubleRangeOf(String key) {
        return DOUBLE_RANGES.get(key);
    }

    /** True for any ai.ground.* group key (single write-side dispatch check). */
    public static boolean isGroundKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }
}
