package com.sixbean.testpike.aiservice.gen.domain.component;

/**
 * One reconciled rule item with its similarity against the generated product
 * text . {@code covered} is {@code similarity >= floor} —
 * the floor is inclusive, matching the acceptance-coverage verdict discipline.
 *
 * @param item the reconciled table-row rule item
 * @param similarity cosine similarity of the item criterion against the
 * product text, in [-1, 1]
 * @param covered whether the product expresses this rule
 */
public record RuleLedgerVerdict(RuleItem item, double similarity, boolean covered) {
}
