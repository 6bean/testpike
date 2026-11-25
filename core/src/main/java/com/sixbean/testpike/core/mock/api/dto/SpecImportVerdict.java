package com.sixbean.testpike.core.mock.api.dto;

/**
 * One structured pre-adjudication for a contract draft row: whether the
 * wizard should pre-select it, why, how a conflict should be handled, what
 * the import would touch, and how trustworthy the sample data and the advice
 * are. Advisory only — it never authorizes a write, the human confirm gate
 * stays the one and only entry into the rule store.
 *
 * @param endpointKey   contract key (METHOD + normalized path) the advice is for
 * @param verdict       {@code recommend} (pre-select) or {@code avoid} (leave out)
 * @param reason        one-sentence business purpose shown on the preview row
 * @param conflictAction for conflicts: {@code skip} / {@code override} /
 *                      {@code rename}; null for fresh endpoints
 * @param conflictStubId id of the already-served rule a conflict points at (null when fresh)
 * @param impactJson    impact reading: 7-day hits of the conflicting rule, references
 * @param realism       data realism of the draft sample body, 0..100
 * @param confidence    confidence of this advice, 0..100
 * @param origin        {@code rule} = deterministic single point, {@code ai} = AI-refined
 * @param storedAtMillis when the advice round was first opened (row created_at) —
 *                       the AI backfill deadline band is measured from this anchor
 */
public record SpecImportVerdict(
        String endpointKey,
        String verdict,
        String reason,
        String conflictAction,
        Long conflictStubId,
        String impactJson,
        int realism,
        int confidence,
        String origin,
        long storedAtMillis) {

    public static final String VERDICT_RECOMMEND = "recommend";
    public static final String VERDICT_AVOID = "avoid";
    public static final String ACTION_SKIP = "skip";
    public static final String ORIGIN_RULE = "rule";
    public static final String ORIGIN_AI = "ai";
}
