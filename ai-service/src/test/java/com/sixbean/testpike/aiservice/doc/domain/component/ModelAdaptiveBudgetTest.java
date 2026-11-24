package com.sixbean.testpike.aiservice.doc.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (AX batch) input-budget self-adaptation — L2 contract for the model-driven
 * document budget. The extraction direct-send threshold is no longer the static
 * K8 value seeded at 5000 tokens: the effective budget equals the routed model
 * catalog row input limit multiplied by a hot-updatable safety factor
 * ({@code ai.doc.budget-safety-factor}, default 0.6); a missing catalog row falls
 * back to 128000 (every in-register text model has at least a 128K window, and
 * an over-sized budget fails loudly while a starved one silently drops detail).
 * Acceptance fixtures cover both the 128K-class and the 1M-class catalog rows.
 */
class ModelAdaptiveBudgetTest {

    @Test
    void budgetFollowsCatalogRowTimesSafetyFactor() {
        // 128K-class catalog row, default factor 0.6 -> 76800
        assertEquals(76800, ModelAdaptiveBudget.effectiveTokens(128000, null));
        // 1M-class catalog row, default factor 0.6 -> 629146 (rounded)
        assertEquals(629146, ModelAdaptiveBudget.effectiveTokens(1048576, null));
        // hot-updated factor takes effect on the next call
        assertEquals(102400, ModelAdaptiveBudget.effectiveTokens(128000, "0.8"));
    }

    @Test
    void missingCatalogRowFallsBackTo128kWindow() {
        // null row (endpoint without a prompt limit) and non-positive values both
        // fall back to the 128000 base window before the factor is applied
        assertEquals(76800, ModelAdaptiveBudget.effectiveTokens(null, null));
        assertEquals(76800, ModelAdaptiveBudget.effectiveTokens(0, null));
        assertEquals(76800, ModelAdaptiveBudget.effectiveTokens(-10, null));
    }

    @Test
    void garbageOrOutOfRangeFactorFallsBackToDefault() {
        // read-side tolerance mirrors BudgetPolicy: blank / non-numeric / out of
        // [0.1, 0.95] each degrade to the seeded 0.6 without throwing
        assertEquals(76800, ModelAdaptiveBudget.effectiveTokens(128000, ""));
        assertEquals(76800, ModelAdaptiveBudget.effectiveTokens(128000, "abc"));
        assertEquals(76800, ModelAdaptiveBudget.effectiveTokens(128000, "1.5"));
        assertEquals(76800, ModelAdaptiveBudget.effectiveTokens(128000, "0.01"));
        assertEquals(76800, ModelAdaptiveBudget.effectiveTokens(128000, "-0.2"));
    }

    @Test
    void smallDocumentGoesDirectSendUnderBothCatalogRows() {
        // C1 posture: the grouping budget is min(window budget, group-max-tokens
        // cap), so the direct-send line is the 8000-token node cap on BOTH
        // catalog rows, not the window itself. A ~7K-token body (comfortably
        // above the legacy static 5000 value this feature replaced) direct-sends
        // under either row; a 90K body groups under either row — the window
        // alone may never decide how coarsely a node reads.
        String smallBody = "a".repeat(7000 * 480 / 512);
        List<Map<String, Object>> small = List.of(Map.of("seq", 1, "content", smallBody));

        ContextBudgetPlanner budget1m = plannerFor(1048576);
        assertFalse(budget1m.needsCompressionByTokens(small),
                "7K-token document must direct-send under the 1M-class row (node cap 8000)");

        ContextBudgetPlanner budget128k = plannerFor(128000);
        assertFalse(budget128k.needsCompressionByTokens(small),
                "7K-token document must direct-send under the 128K-class row (node cap 8000)");

        String bigBody = "a".repeat(90000 * 480 / 512);
        List<Map<String, Object>> big = List.of(Map.of("seq", 1, "content", bigBody));
        assertTrue(budget1m.needsCompressionByTokens(big),
                "90K-token document must group under the 1M row once the node cap binds");
        assertTrue(budget128k.needsCompressionByTokens(big),
                "90K-token document must group under the 128K-class row budget of 76800");
    }

    private static ContextBudgetPlanner plannerFor(Integer catalogPromptLimit) {
        return new ContextBudgetPlanner(ModelAdaptiveBudget.effectiveTokens(catalogPromptLimit, null));
    }
}
