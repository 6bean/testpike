package com.sixbean.testpike.aiservice.llm.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Budgeted prompt-segment assembler — single source for every injection
 * bridge that splices reference blocks into a generation prompt (R40: the
 * few-shot bridge, the DDL dictionary bridge and the contract truth-source
 * bridge all ran the same rule; a fourth copy was the trigger to extract).
 * <p>Single rule, never double-deducted (A4 discipline): the effective budget
 * is min(doc budget, endpoint prompt_limit_tokens) through
 * {@link PromptSizeGuard#effectiveLimit}; every block is estimated against the
 * accumulated segment and an over-budget block is dropped whole at the tail —
 * a partial line must never reach the prompt.
 * <p>All-dropped is an honest empty segment: the caller's prompt stays at its
 * legacy bytes and the event is only logged (fail-soft by contract, the
 * generation chain never breaks here).
 */
public final class BudgetedPromptSegment {

    private static final Logger log = LoggerFactory.getLogger(BudgetedPromptSegment.class);

    private BudgetedPromptSegment() {
    }

    /**
     * Compose one injectable segment.
     *
     * @param label             caller-facing tag for the drop warn
     *                          (include the project/task handle — e.g.
     *                          {@code "contract brief projectId=7"})
     * @param estimator         token counter of record ({@link TokenEstimator}
     *                          single count source, no char heuristics here)
     * @param docBudget         token budget read hot from K8 by the caller
     * @param modelLimitTokens  routed endpoint cap, null = no model-side limit
     * @param header            section header prepended once when anything is
     *                          kept (part of every estimate, so the real
     *                          delivered size is what the budget sees)
     * @param blocks            candidate blocks in keep-first order
     * @return header + kept blocks, or "" when nothing fits
     */
    public static String compose(String label, TokenEstimator estimator, int docBudget,
                                 Integer modelLimitTokens, String header, List<String> blocks) {
        int budget = PromptSizeGuard.effectiveLimit(docBudget, modelLimitTokens);
        StringBuilder segment = new StringBuilder();
        int injected = 0;
        int dropped = 0;
        for (String block : blocks) {
            int estIfAdded = estimator.countTokens(header + segment + block);
            if (estIfAdded <= budget) {
                segment.append(block);
                injected++;
            } else {
                dropped++;
            }
        }
        if (injected == 0) {
            log.warn("{}: every block over budget, zero injection budget={}", label, budget);
            return "";
        }
        if (dropped > 0) {
            log.warn("{}: dropped {} over-budget blocks (kept {})", label, dropped, injected);
        }
        return header + segment;
    }
}
