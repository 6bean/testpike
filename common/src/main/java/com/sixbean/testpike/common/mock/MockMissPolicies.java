package com.sixbean.testpike.common.mock;

import java.util.List;

/**
 * The space-level miss ladder : what an unmatched request does.
 * <p>Single spectrum shared by the core save chain (validation), the core
 * policy door and the mock-server loader (tolerant read) — red line 6 forbids
 * a second list of rung names anywhere.
 * <ul>
 * <li>{@code passthrough} — forward unmatched traffic to the bound target
 * whenever the space has one; recording frames only while a box is open.</li>
 * <li>{@code reject} — answer the honest 404+trace only; never forward,
 * never ask the model.</li>
 * <li>{@code llm-fallback} — the derived ladder spaces had before the policy
 * column existed: open-box proxy rung, then the gated AI rung, then 404.</li>
 * </ul>
 */
public final class MockMissPolicies {

    public static final String PASSTHROUGH = "passthrough";
    public static final String REJECT = "reject";
    public static final String LLM_FALLBACK = "llm-fallback";

    /** Chosen by spaces that never picked one (and by tolerant reads of older pins). */
    public static final String DEFAULT_POLICY = LLM_FALLBACK;

    public static final List<String> ALL = List.of(PASSTHROUGH, REJECT, LLM_FALLBACK);

    private MockMissPolicies() {
    }

    /** True when the value names a known rung (null-safe; null is never known). */
    public static boolean isKnown(String value) {
        return value != null && ALL.contains(value);
    }

    /** Rung name for storage: null/blank decodes to the default, never a guess. */
    public static String stored(String value) {
        return value == null || value.isBlank() ? DEFAULT_POLICY : value.trim();
    }
}
