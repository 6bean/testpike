package com.sixbean.testpike.core.config.domain.component;

import java.util.Map;
import java.util.Set;

/**
 * Write-side value ranges for the ai.ocr.* vision-less fallback key group
 * .
 *
 * <p>Mirrors the AiChatKeyRanges / AiGroundKeyRanges precedent: the K8
 * convention puts no CHECK constraint in the DB, this table is the single
 * source of intervals for the write guard in ConfigServiceImpl, and the
 * readers in ai-service (ChatUserImageResolver / FailureScreenshotResolver)
 * fall back to their shipped defaults on absent or unparsable values. The
 * density floor guards the "never force-interpret a sparse picture"
 * posture — far too high a floor would silently disable the fallback.
 */
public final class AiOcrKeyRanges {

    /** Vision-less OCR fallback switch (factory on, R32). */
    public static final String KEY_FALLBACK_ENABLED = "ai.ocr.fallback.enabled";
    /** Line count at which a transcription counts as text-dense (1~20). */
    public static final String KEY_FALLBACK_LINE_MIN = "ai.ocr.fallback-line-min";

    /** Int keys the group interval-guards: key → {lower, upper}. */
    private static final Map<String, int[]> INT_RANGES = Map.of(
            KEY_FALLBACK_LINE_MIN, new int[]{1, 20});

    /** Bool keys the group accepts only true/false for. */
    private static final Set<String> BOOL_KEYS = Set.of(KEY_FALLBACK_ENABLED);

    /** Prefix of the whole group (write-side entry check). */
    public static final String KEY_PREFIX = "ai.ocr.";

    private AiOcrKeyRanges() {
    }

    /** Interval for the group's int keys, or null when not interval-guarded. */
    public static int[] rangeOf(String key) {
        return INT_RANGES.get(key);
    }

    /** True for the group's bool switch keys. */
    public static boolean isBoolKey(String key) {
        return BOOL_KEYS.contains(key);
    }

    /** True for any ai.ocr.* group key (single write-side dispatch check). */
    public static boolean isOcrKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }
}
