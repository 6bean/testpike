package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-item semantic reconciliation of the rule ledger against the generated
 * guards against written WRONG, this component answers written MISSING.
 *
 * <p>The table-row items of {@link RuleItemExtractor} are reconciled through
 * {@link SemanticMatcher} — the repository's only in-memory cosine source
 * (R40, no second copy). One batched round-trip embeds every criterion plus
 * the product text; {@code similarity >= floor} marks the item covered.
 *
 * <p>Honest-degradation contract: a failing embedding chain (mock mode /
 * unconfigured provider fail closed with a human-readable
 * {@link ServiceException}) yields {@code embeddingDegraded} with NO
 * fabricated verdicts, so the caller keeps the legacy judgement and names a
 * WARN. Prose candidates are counted for human adjudication and never
 * auto-judged, and a ledger without table rows costs zero outbound calls.
 * Pure component: no DB, no config reads — the hot-switch wiring arrives
 * with the service seam (AI generation repair ring).
 */
public class RuleLedgerReconciler {

    private final RuleItemExtractor extractor;
    private final SemanticMatcher matcher;

    public RuleLedgerReconciler(RuleItemExtractor extractor, SemanticMatcher matcher) {
        this.extractor = extractor;
        this.matcher = matcher;
    }

    /**
     * Reconciles every table-row rule item of the anchored block set against
     * {@code productText}.
     *
     * @param blocks          anchored original-text blocks (null-safe: no blocks, no ledger)
     * @param productText     the generated product text reconciled against
     * @param projectId       routing anchor of the embedding chain
     * @param similarityFloor covered test threshold, inclusive
     */
    public RuleLedgerOutcome reconcile(List<RuleSourceBlock> blocks, String productText,
                                       long projectId, double similarityFloor) {
        List<RuleItem> items = extractor.extract(blocks);
        List<RuleItem> rows = new ArrayList<>();
        int proseCandidates = 0;
        for (RuleItem item : items) {
            if (item.kind() == RuleItemKind.PROSE_CANDIDATE) {
                proseCandidates++;
            } else {
                rows.add(item);
            }
        }
        if (rows.isEmpty()) {
            return new RuleLedgerOutcome(List.of(), proseCandidates, false);
        }
        List<String> criteria = new ArrayList<>(rows.size());
        for (RuleItem row : rows) {
            criteria.add(row.criterionText());
        }
        List<Double> similarities;
        try {
            similarities = matcher.similarities(criteria,
                    productText == null ? "" : productText, projectId);
        } catch (ServiceException chainUnavailable) {
            return new RuleLedgerOutcome(List.of(), proseCandidates, true);
        }
        List<RuleLedgerVerdict> verdicts = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            double similarity = similarities.get(i);
            verdicts.add(new RuleLedgerVerdict(rows.get(i), similarity,
                    similarity >= similarityFloor));
        }
        return new RuleLedgerOutcome(verdicts, proseCandidates, false);
    }
}
