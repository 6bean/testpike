package com.sixbean.testpike.admin.aichat.app.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Date-group bucket spec for the dialog session list .
 * <p>Single parsing point for the K8 key ai.chat.session-group-days: an
 * ascending comma-separated list of day thresholds ("1,7,30" → groups today /
 * last 7 days / last 30 days / earlier). Any malformed shape (unparsable,
 * blank element, non-positive, non-ascending, duplicate) falls back to the
 * documented default so a typo in the hot key never breaks the panel —
 * same degrade-to-default posture as the integer config reads on the SSE lane.
 */
public final class ChatSessionGroupDays {

    /** The one and only key name; the migration seed and every reader share it. */
    public static final String CONFIG_KEY = "ai.chat.session-group-days";

    /** Factory default, identical to the seed row value (single source of truth). */
    public static final String DEFAULT_SPEC = "1,7,30";

    private ChatSessionGroupDays() {
    }

    /**
     * Parse a raw spec into day thresholds, falling back to 1,7,30 when the
     * value is unusable. Blank/unreadable input is treated as malformed.
     */
    public static List<Integer> parseOrFallback(String rawSpec) {
        List<Integer> parsed = parse(rawSpec);
        return parsed == null ? List.of(1, 7, 30) : parsed;
    }

    /**
     * Strict parse: ascending positive integers only; anything else returns
     * null (caller decides the fallback). Returns an unmodifiable list.
     */
    private static List<Integer> parse(String rawSpec) {
        if (rawSpec == null || rawSpec.isBlank()) {
            return null;
        }
        List<Integer> buckets = new ArrayList<>();
        for (String part : rawSpec.split(",")) {
            int days;
            try {
                days = Integer.parseInt(part.trim());
            } catch (NumberFormatException notAnInt) {
                return null;
            }
            // Strictly ascending and positive: a non-increasing list would
            // render an empty or misleading group in the panel.
            if (days <= 0 || (!buckets.isEmpty() && days <= buckets.get(buckets.size() - 1))) {
                return null;
            }
            buckets.add(days);
        }
        return buckets.isEmpty() ? null : List.copyOf(buckets);
    }
}
