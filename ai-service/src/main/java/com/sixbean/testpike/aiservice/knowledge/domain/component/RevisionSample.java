package com.sixbean.testpike.aiservice.knowledge.domain.component;

/**
 * One project-level few-shot revision sample read from
 * {@code tp_ai_fewshot_revision} .
 * Carries exactly what the generation few-shot bridge renders: the outcome
 * family, the producing AI text and the human result (NULL for the delete
 * family, which then shows the AI side only plus a deprecation note).
 *
 * @param outcome corrected / deprecated (mirrors the pool CHECK constraint)
 * @param aiText producing AI text (before side of the pair)
 * @param humanText human result after the edit; NULL when the reaction was a deletion
 */
public record RevisionSample(String outcome, String aiText, String humanText) {

    /** Ledger outcome value marking a kept heavy rewrite. */
    public static final String OUTCOME_CORRECTED = "corrected";

    /** Ledger outcome value marking a deleted / rewritten-past-recognition case. */
    public static final String OUTCOME_DEPRECATED = "deprecated";

    /** Delete family: nothing to show on the human side. */
    public boolean deleted() {
        return humanText == null || humanText.isBlank();
    }
}
