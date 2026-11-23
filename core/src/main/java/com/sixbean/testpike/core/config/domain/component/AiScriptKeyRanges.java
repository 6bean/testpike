package com.sixbean.testpike.core.config.domain.component;

import java.util.Map;

/**
 *
 * selfheal-rounds 0~30 (0 = self-healing off stays valid, aligned with the
 * review.remind-hours zero-means-off baseline; widened by the B-tier relaxation),
 *
 *
 */
public final class AiScriptKeyRanges {

    /** Key → {lower, upper} for every int key of the family (missing = foreign key).
 * segment keys (V489): width 0~100 with 0 = slicing off, timeout 800~3600 keeps
 * the >=800s per-hop floor, retry 0~3 with 0 = no retry. */
    private static final Map<String, int[]> INT_RANGES = Map.ofEntries(
            Map.entry("ai.script.max-loop", new int[]{1, 100}),
            Map.entry("ai.script.max-steps", new int[]{1, 500}),
            Map.entry("ai.script.max-nest-depth", new int[]{1, 20}),
            Map.entry("ai.script.selfheal-rounds", new int[]{0, 30}),
            Map.entry("ai.script.assert-timeout-sec", new int[]{1, 300}),
            Map.entry("ai.script.default-wait-sec", new int[]{0, 60}),
            Map.entry("ai.script.screenshot-mode", new int[]{1, 3}),
            Map.entry("ai.script.fail-policy", new int[]{1, 2}),
            Map.entry("ai.script.slice-steps", new int[]{0, 100}),
            Map.entry("ai.script.slice-timeout-seconds", new int[]{800, 3600}),
            Map.entry("ai.script.slice-retry", new int[]{0, 3}));

    
    public static final String KEY_PREFIX = "ai.script.";

    private AiScriptKeyRanges() {
    }

    
    public static int[] rangeOf(String key) {
        return INT_RANGES.get(key);
    }

    
    public static boolean isScriptKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }
}
