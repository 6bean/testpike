package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigDoubles;

/**
 * Model-driven document input budget .
 *
 * <p>The extraction chain used to size its direct-send window from the static K8
 * key {@code ai.doc.token-budget} (seeded at 5000 tokens), which misrouted small
 * documents into the summarisation path even when the routed model could hold
 * them in one request. The effective budget is now derived per call from the
 * routed model catalog row: {@code catalogInputLimit x safetyFactor}, where the
 * safety factor is the hot-updatable K8 key
 * {@link #KEY_SAFETY_FACTOR} (default {@link #DEFAULT_SAFETY_FACTOR}).
 *
 * <p>When no catalog row is readable the base window falls back to
 * {@link #FALLBACK_CATALOG_TOKENS} (128000): every in-register text model has
 * at least a 128K input window, and the failure modes are asymmetric — an
 * over-sized budget fails loudly and recoverably at the endpoint, a starved
 * one silently drops detail. The endpoint-side clamp in
 * {@code PromptSizeGuard.effectiveLimit} stays in place on top of this value.
 *
 * <p>Read-side tolerance mirrors {@link BudgetPolicy}: blank / non-numeric /
 * out-of-range factor values degrade to the seeded default instead of throwing,
 * so a mis-typed config never aborts an extraction job. Write-side interval
 * validation belongs to the core config service.
 */
public final class ModelAdaptiveBudget {

    /** K8 hot-reload key holding the safety factor as a decimal string. */
    public static final String KEY_SAFETY_FACTOR = "ai.doc.budget-safety-factor";

    /** Seeded default factor; may be raised later as the residual-gap rate drops. */
    public static final double DEFAULT_SAFETY_FACTOR = 0.6;

    /** Read-side accepted interval (values outside it degrade to the default). */
    public static final double FACTOR_MIN = 0.1;
    public static final double FACTOR_MAX = 0.95;

    /** Base input window assumed when the catalog row is unreadable. */
    public static final int FALLBACK_CATALOG_TOKENS = 128000;

    private ModelAdaptiveBudget() {
    }

    /**
     * Effective document budget in tokens: catalog input limit scaled by the
     * configured safety factor, rounded to the nearest token.
     *
     * @param catalogInputLimit the routed model row input limit
     *                          ({@code tp_llm_config.prompt_limit_tokens}), null
     *                          or non-positive falls back to
     *                          {@link #FALLBACK_CATALOG_TOKENS}
     * @param rawFactor         raw K8 value of {@link #KEY_SAFETY_FACTOR},
     *                          tolerated per {@link #factorOf}
     */
    public static int effectiveTokens(Integer catalogInputLimit, String rawFactor) {
        int base = catalogInputLimit == null || catalogInputLimit <= 0
                ? FALLBACK_CATALOG_TOKENS : catalogInputLimit;
        return (int) Math.round(base * factorOf(rawFactor));
    }

    /** Raw K8 value to safety factor; blank / non-numeric / out-of-range degrade to the default. */
    public static double factorOf(String rawFactor) {
        // the interval-tolerant decimal read folded into the
        // shared K8 reader (R40 second consumer) — same semantics, one source.
        return SysConfigDoubles.parse(rawFactor, DEFAULT_SAFETY_FACTOR, FACTOR_MIN, FACTOR_MAX);
    }
}
