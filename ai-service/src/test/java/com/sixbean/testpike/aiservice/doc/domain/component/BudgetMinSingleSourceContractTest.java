package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.component.PromptSizeGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * (A1) — the min(window, cap) budget rule has exactly one
 * implementation: {@link PromptSizeGuard#effectiveLimit}. Before this segment
 * ContextBudgetPlanner carried its own copies of the same arithmetic
 * These tests pin the planner faces to the canonical rule across every
 * argument face (null / zero / negative cap, cap above and below the
 * window) so the delegation refactor is provably zero-drift, and they run
 * green both before the change (baseline characterization) and after it.
 */
class BudgetMinSingleSourceContractTest {

    /** Canonical rule under test — the single source of record. */
    private static int sharedRule(int budget, Integer cap) {
        return PromptSizeGuard.effectiveLimit(budget, cap);
    }

    @Test
    void effectiveBudgetFaceMatchesSharedRuleOnEveryCapFace() {
        int base = 60_000;
        // cap absent (0 / negative) keeps the original budget untouched.
        assertEquals(sharedRule(base, 0),
                new ContextBudgetPlanner(base).withEffectiveBudget(0).tokenBudget());
        assertEquals(sharedRule(base, -5),
                new ContextBudgetPlanner(base).withEffectiveBudget(-5).tokenBudget());
        // cap below the window wins.
        assertEquals(sharedRule(base, 12_800),
                new ContextBudgetPlanner(base).withEffectiveBudget(12_800).tokenBudget());
        // cap above the window keeps the window.
        assertEquals(sharedRule(base, 90_000),
                new ContextBudgetPlanner(base).withEffectiveBudget(90_000).tokenBudget());
    }

    @Test
    void groupBudgetFaceMatchesSharedRuleAcrossWindowsAndCaps() {
        ContextBudgetPlanner factoryCaps = new ContextBudgetPlanner(598_771);
        assertEquals(sharedRule(factoryCaps.tokenBudget(), ContextBudgetPlanner.DEFAULT_GROUP_MAX_TOKENS),
                factoryCaps.groupBudgetTokens(),
                "factory node cap inside a huge window");

        ContextBudgetPlanner smallWindow = new ContextBudgetPlanner(3_000)
                .withEffectiveBudget(3_000);
        assertEquals(sharedRule(smallWindow.tokenBudget(), ContextBudgetPlanner.DEFAULT_GROUP_MAX_TOKENS),
                smallWindow.groupBudgetTokens(),
                "window smaller than the node cap keeps the window");

        ContextBudgetPlanner tightened = new ContextBudgetPlanner(20_000)
                .withGroupCeilings(4_096, 40, 2_000, 4.0);
        assertEquals(sharedRule(tightened.tokenBudget(), 4_096),
                tightened.groupBudgetTokens(),
                "explicit node cap below the window bites");

        ContextBudgetPlanner loosened = new ContextBudgetPlanner(2_048)
                .withGroupCeilings(9_000, 40, 2_000, 4.0);
        assertEquals(sharedRule(loosened.tokenBudget(), 9_000),
                loosened.groupBudgetTokens(),
                "node cap above a tiny window never inflates it");
    }

    @Test
    void matrixEquivalencePlannerFacesAgainstSharedRule() {
        int[] budgets = {512, 1_000, 5_000, 8_000, 598_771};
        int[] caps = {-1, 0, 256, 2_048, 8_000, 100_000, 120_000};
        for (int budget : budgets) {
            for (int cap : caps) {
                ContextBudgetPlanner planner =
                        new ContextBudgetPlanner(budget).withEffectiveBudget(cap);
                assertEquals(sharedRule(budget, cap), planner.tokenBudget(),
                        "effective-budget face budget=" + budget + " cap=" + cap);
                int clampedCap = Math.min(
                        Math.max(cap, ContextBudgetPlanner.MIN_GROUP_MAX_TOKENS),
                        ContextBudgetPlanner.MAX_GROUP_MAX_TOKENS);
                ContextBudgetPlanner grouped = planner
                        .withGroupCeilings(clampedCap, 40, 2_000, 4.0);
                assertEquals(sharedRule(planner.tokenBudget(), clampedCap),
                        grouped.groupBudgetTokens(),
                        "group-budget face budget=" + budget + " cap=" + cap);
            }
        }
    }
}
