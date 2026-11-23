package com.sixbean.testpike.aiservice.gen.domain.component;

import java.util.Optional;

/**
 * Measured-confidence composite : replaces the model's
 * self-reported 1/2/3 with a score built from signals the system actually
 * observed this run —
 * <ol>
 * <li>fidelity ratio: claims the draft got past the faithfulness gate
 * , {@link FidelityVerdict#fidelityRatio});</li>
 * <li>coverage ratio: acceptance criteria the draft covers
 * ({@link CoverageChecker}, covered / (covered + uncovered));</li>
 * <li>source coverage: assertive claims carrying a checkable anchor
 * ({@code EvidenceAnchorGate.sourceCoverage}).</li>
 * </ol>
 * Equal-weight mean over the signals that ran: an absent signal leaves the
 * denominator instead of dragging the score down, and when nothing ran the
 * composite returns {@link Optional#empty} so the caller keeps the
 * self-report — a measurement is never fabricated out of thin air.
 * (≥ 0.75 = high, ≥ 0.5 = mid, else low). The semantic upgrade of signal 2
 * (A5 embedding reconciliation) is and must not be duplicated here.
 */
public class ConfidenceMeasurer {

    /** Floor of the high band (inclusive). */
    static final double HIGH_FLOOR = 0.75;

    /** Floor of the mid band (inclusive); below it is low. */
    static final double MID_FLOOR = 0.5;

    /**
     * Combine the observed signals into a level, or empty when no signal ran.
     *
     * @param fidelityRatio matched/audited claims of the faithfulness gate,
     *                      null when the gate did not run this task
     * @param coverageRatio covered/(covered+uncovered) acceptance criteria,
     *                      null when there were no criteria
     * @param sourceRatio   anchored share of assertive claims, null when the
     *                      draft carried no auditable claim
     */
    public Optional<ConfidenceMeasure> measure(Double fidelityRatio, Double coverageRatio,
                                               Double sourceRatio) {
        double sum = 0;
        int weights = 0;
        for (Double signal : new Double[]{fidelityRatio, coverageRatio, sourceRatio}) {
            if (signal != null) {
                sum += Math.max(0, Math.min(1, signal));
                weights++;
            }
        }
        if (weights == 0) {
            return Optional.empty();
        }
        double score = sum / weights;
        int level = levelOf(score);
        return Optional.of(new ConfidenceMeasure(score, level, level == 3));
    }

    /** Band the score falls into: ≥ 0.75 = 1, ≥ 0.5 = 2, else 3. */
    public int levelOf(double score) {
        if (score >= HIGH_FLOOR) {
            return 1;
        }
        if (score >= MID_FLOOR) {
            return 2;
        }
        return 3;
    }
}
